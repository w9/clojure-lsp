(ns clojure-lsp.server
  (:require
   [clojure-lsp.coercer :as coercer]
   [clojure-lsp.config :as config]
   [clojure-lsp.db :as db]
   [clojure-lsp.feature.file-management :as f.file-management]
   [clojure-lsp.feature.refactor :as f.refactor]
   [clojure-lsp.feature.semantic-tokens :as semantic-tokens]
   [clojure-lsp.feature.test-tree :as f.test-tree]
   [clojure-lsp.handlers :as handlers]
   [clojure-lsp.nrepl :as nrepl]
   [clojure-lsp.producer :as producer]
   [clojure-lsp.settings :as settings]
   [clojure-lsp.shared :as shared]
   [clojure.core.async :refer [<! go go-loop thread timeout]]
   [clojure.java.data :as j]
   [taoensso.timbre :as log])
  (:import
   (clojure_lsp
     ClojureLanguageServer
     ClojureLanguageClient)
   (clojure_lsp.feature.clojuredocs ClojuredocsParams)
   (clojure_lsp.feature.cursor_info CursorInfoParams)
   (java.util.concurrent CompletableFuture
                         CompletionException)
   (java.util.function Supplier)
   (org.eclipse.lsp4j
     ApplyWorkspaceEditParams
     CallHierarchyIncomingCallsParams
     CallHierarchyOutgoingCallsParams
     CallHierarchyPrepareParams
     CodeActionParams
     CodeActionOptions
     CodeLens
     CodeLensParams
     CodeLensOptions
     CodeLensWorkspaceCapabilities
     CompletionItem
     CompletionOptions
     CompletionParams
     DeclarationParams
     DefinitionParams
     DidChangeConfigurationParams
     DidChangeTextDocumentParams
     DidChangeWatchedFilesParams
     DidChangeWatchedFilesRegistrationOptions
     DidCloseTextDocumentParams
     DidOpenTextDocumentParams
     DidSaveTextDocumentParams
     DocumentFormattingParams
     DocumentHighlightParams
     DocumentRangeFormattingParams
     DocumentSymbolParams
     ExecuteCommandOptions
     ExecuteCommandParams
     FileSystemWatcher
     HoverParams
     ImplementationParams
     InitializeParams
     InitializeResult
     InitializedParams
     LinkedEditingRangeParams
     MessageActionItem
     PrepareRenameParams
     ReferenceParams
     Registration
     RegistrationParams
     RenameParams
     RenameOptions
     SaveOptions
     SemanticTokensLegend
     SemanticTokensParams
     SemanticTokensRangeParams
     SemanticTokensWithRegistrationOptions
     ServerCapabilities
     SignatureHelpOptions
     SignatureHelpParams
     TextDocumentSyncKind
     TextDocumentSyncOptions
     WindowClientCapabilities
     WorkspaceSymbolParams)
   (org.eclipse.lsp4j.jsonrpc ResponseErrorException
                              Launcher)
   (org.eclipse.lsp4j.services LanguageServer TextDocumentService WorkspaceService))
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce formatting (atom false))

(defmacro ^:private start [id & body]
  `(let [~'_start-time (System/nanoTime)
         ~'_id ~id]
     (do ~@body)))

(defmacro ^:private end
  ([expr]
   `(end ~expr false))
  ([expr extra-log-fn]
   `(let [~'_result (try
                      ~expr
                      (catch Throwable ex#
                        (if (instance? ResponseErrorException ex#)
                          (throw (CompletionException. ex#))
                          (log/error ex#))))]
      (try
        (let [duration# (quot (- (System/nanoTime) ~'_start-time) 1000000)]
          (if ~extra-log-fn
            (log/debug ~'_id (format "%sms - %s" duration# (~extra-log-fn ~'_result)))
            (log/debug ~'_id (format "%sms" duration#))))
        (catch Throwable ex#
          (log/error ex#)))
      ~'_result)))

(defmacro ^:private sync-notification
  [params handler]
  `(end
     (->> ~params
          coercer/java->clj
          ~handler)))

(defmacro ^:private sync-request
  ([params handler response-spec]
   `(sync-request ~params ~handler ~response-spec false))
  ([params handler response-spec extra-log-fn]
   `(end
      (->> ~params
           coercer/java->clj
           ~handler
           (coercer/conform-or-log ~response-spec))
      ~extra-log-fn)))

(defmacro ^:private async-request
  ([params handler response-spec]
   `(async-request ~params ~handler ~response-spec false))
  ([params handler response-spec extra-log-fn]
   `(CompletableFuture/supplyAsync
      (reify Supplier
        (get [this]
          (sync-request ~params ~handler ~response-spec ~extra-log-fn))))))

(defmacro ^:private async-notification
  [params handler]
  `(CompletableFuture/supplyAsync
     (reify Supplier
       (get [this]
         (sync-notification ~params ~handler)))))

(deftype LSPTextDocumentService []
  TextDocumentService
  (^void didOpen [_ ^DidOpenTextDocumentParams params]
    (start :didOpen
           (sync-notification params handlers/did-open)))

  (^void didChange [_ ^DidChangeTextDocumentParams params]
    (start :didChange
           (sync-notification params handlers/did-change)))

  (^void didSave [_ ^DidSaveTextDocumentParams params]
    (start :didSave
           (future
             (sync-notification params handlers/did-save)))
    (CompletableFuture/completedFuture 0))

  (^void didClose [_ ^DidCloseTextDocumentParams params]
    (start :didClose
           (async-notification params handlers/did-close)))

  (^CompletableFuture references [_ ^ReferenceParams params]
    (start :references
           (async-request params handlers/references ::coercer/locations)))

  (^CompletableFuture completion [_ ^CompletionParams params]
    (start :completion
           (async-request params handlers/completion ::coercer/completion-items (fn [items]
                                                                                  (format "total items: %s" (count items))))))

  (^CompletableFuture resolveCompletionItem [_ ^CompletionItem item]
    (start :resolveCompletionItem
           (async-request item handlers/completion-resolve-item ::coercer/completion-item)))

  (^CompletableFuture prepareRename [_ ^PrepareRenameParams params]
    (start :prepare-rename
           (async-request params handlers/prepare-rename ::coercer/prepare-rename-or-error)))

  (^CompletableFuture rename [_ ^RenameParams params]
    (start :rename
           (async-request params handlers/rename ::coercer/workspace-edit-or-error)))

  (^CompletableFuture hover [_ ^HoverParams params]
    (start :hover
           (async-request params handlers/hover ::coercer/hover)))

  (^CompletableFuture signatureHelp [_ ^SignatureHelpParams params]
    (start :signatureHelp
           (async-request params handlers/signature-help ::coercer/signature-help)))

  (^CompletableFuture formatting [_ ^DocumentFormattingParams params]
    (start :formatting
           (async-request params handlers/formatting ::coercer/edits)))

  (^CompletableFuture rangeFormatting [_this ^DocumentRangeFormattingParams params]
    (start :rangeFormatting
           (end
             (let [result (when (compare-and-set! formatting false true)
                            (try
                              (let [doc-id (coercer/document->uri (.getTextDocument params))
                                    range (.getRange params)
                                    start (.getStart range)
                                    end (.getEnd range)]
                                (coercer/conform-or-log ::coercer/edits (#'handlers/range-formatting
                                                                         doc-id
                                                                         {:row (inc (.getLine start))
                                                                          :col (inc (.getCharacter start))
                                                                          :end-row (inc (.getLine end))
                                                                          :end-col (inc (.getCharacter end))})))
                              (catch Exception e
                                (log/error e))
                              (finally
                                (reset! formatting false))))]
               (CompletableFuture/completedFuture
                 result)))))

  (^CompletableFuture codeAction [_ ^CodeActionParams params]
    (start :codeAction
           (async-request params handlers/code-actions ::coercer/code-actions)))

  (^CompletableFuture codeLens [_ ^CodeLensParams params]
    (start :codeLens
           (async-request params handlers/code-lens ::coercer/code-lenses)))

  (^CompletableFuture resolveCodeLens [_ ^CodeLens params]
    (start :resolveCodeLens
           (async-request params handlers/code-lens-resolve ::coercer/code-lens)))

  (^CompletableFuture definition [_ ^DefinitionParams params]
    (start :definition
           (async-request params handlers/definition ::coercer/location)))

  (^CompletableFuture declaration [_ ^DeclarationParams params]
    (start :declaration
           (async-request params handlers/declaration ::coercer/location)))

  (^CompletableFuture implementation [_ ^ImplementationParams params]
    (start :implementation
           (async-request params handlers/implementation ::coercer/locations)))

  (^CompletableFuture documentSymbol [_ ^DocumentSymbolParams params]
    (start :documentSymbol
           (async-request params handlers/document-symbol ::coercer/document-symbols)))

  (^CompletableFuture documentHighlight [_ ^DocumentHighlightParams params]
    (start :documentHighlight
           (async-request params handlers/document-highlight ::coercer/document-highlights)))

  (^CompletableFuture semanticTokensFull [_ ^SemanticTokensParams params]
    (start :semanticTokensFull
           (async-request params handlers/semantic-tokens-full ::coercer/semantic-tokens)))

  (^CompletableFuture semanticTokensRange [_ ^SemanticTokensRangeParams params]
    (start :semanticTokensRange
           (async-request params handlers/semantic-tokens-range ::coercer/semantic-tokens)))

  (^CompletableFuture prepareCallHierarchy [_ ^CallHierarchyPrepareParams params]
    (start :prepareCallHierarchy
           (async-request params handlers/prepare-call-hierarchy ::coercer/call-hierarchy-items)))

  (^CompletableFuture callHierarchyIncomingCalls [_ ^CallHierarchyIncomingCallsParams params]
    (start :callHierarchyIncomingCalls
           (async-request params handlers/call-hierarchy-incoming ::coercer/call-hierarchy-incoming-calls)))

  (^CompletableFuture callHierarchyOutgoingCalls [_ ^CallHierarchyOutgoingCallsParams params]
    (start :callHierarchyOutgoingCalls
           (async-request params handlers/call-hierarchy-outgoing ::coercer/call-hierarchy-outgoing-calls)))

  (^CompletableFuture linkedEditingRange [_ ^LinkedEditingRangeParams params]
    (start :linkedEditingRange
           (async-request params handlers/linked-editing-ranges ::coercer/linked-editing-ranges-or-error))))

(deftype LSPWorkspaceService []
  WorkspaceService
  (^CompletableFuture executeCommand [_ ^ExecuteCommandParams params]
    (start :executeCommand
           (future
             (sync-notification params handlers/execute-command)))
    (CompletableFuture/completedFuture 0))

  (^void didChangeConfiguration [_ ^DidChangeConfigurationParams params]
    (log/warn (coercer/java->clj params)))

  (^void didChangeWatchedFiles [_ ^DidChangeWatchedFilesParams params]
    (start :didChangeWatchedFiles
           (async-notification params handlers/did-change-watched-files)))

  ;; TODO wait for lsp4j release
  #_(^void didDeleteFiles [_ ^DeleteFilesParams params]
                          (start :didSave
                                 (sync-handler params handlers/did-delete-files)))

  (^CompletableFuture symbol [_ ^WorkspaceSymbolParams params]
    (start :workspaceSymbol
           (async-request params handlers/workspace-symbols ::coercer/workspace-symbols))))

(defn ^:private client-settings [^InitializeParams params]
  (-> params
      coercer/java->clj
      :initializationOptions
      (or {})
      shared/keywordize-first-depth
      (settings/clean-client-settings)))

(defn ^:private client-capabilities [^InitializeParams params]
  (some->> params
           .getCapabilities
           (coercer/conform-or-log ::coercer/client-capabilities)))

;; Called from java
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn extension [method & args]
  (start :extension
         (CompletableFuture/completedFuture
           (end
             (apply #'handlers/extension method (coercer/java->clj args))))))

(defn ^:private start-parent-process-liveness-probe!
  [ppid server]
  (go-loop []
    (<! (timeout 5000))
    (if (shared/process-alive? ppid)
      (recur)
      (do
        (log/info "Parent process" ppid "is not running - exiting server")
        (.exit ^LanguageServer server)))))

(deftype ClojureLSPServer []
  ClojureLanguageServer
  (^CompletableFuture initialize [this ^InitializeParams params]
    (start :initialize
           (end
             (do
               (log/info "Initializing...")
               (handlers/initialize (.getRootUri params)
                                    (client-capabilities params)
                                    (client-settings params)
                                    (some-> (.getWorkDoneToken params) .get str))
               (when-let [parent-process-id (.getProcessId params)]
                 (start-parent-process-liveness-probe! parent-process-id this))
               (let [settings (settings/all db/db)]
                 (CompletableFuture/completedFuture
                   (InitializeResult. (doto (ServerCapabilities.)
                                        (.setDocumentHighlightProvider true)
                                        (.setHoverProvider true)
                                        (.setDeclarationProvider true)
                                        (.setImplementationProvider true)
                                        (.setSignatureHelpProvider (SignatureHelpOptions. []))
                                        (.setCallHierarchyProvider true)
                                        (.setLinkedEditingRangeProvider true)
                                        (.setCodeActionProvider (CodeActionOptions. (vec (vals coercer/code-action-kind))))
                                        (.setCodeLensProvider (CodeLensOptions. true))
                                        (.setReferencesProvider true)
                                        (.setRenameProvider (RenameOptions. true))
                                        (.setDefinitionProvider true)
                                        (.setDocumentFormattingProvider ^Boolean (:document-formatting? settings))
                                        (.setDocumentRangeFormattingProvider ^Boolean (:document-range-formatting? settings))
                                        (.setDocumentSymbolProvider true)
                                        (.setWorkspaceSymbolProvider true)
                                        (.setSemanticTokensProvider (when (or (not (contains? settings :semantic-tokens?))
                                                                              (:semantic-tokens? settings))
                                                                      (doto (SemanticTokensWithRegistrationOptions.)
                                                                        (.setLegend (doto (SemanticTokensLegend.
                                                                                            semantic-tokens/token-types-str
                                                                                            semantic-tokens/token-modifiers-str)))
                                                                        (.setRange true)
                                                                        (.setFull true))))
                                        (.setExecuteCommandProvider (doto (ExecuteCommandOptions.)
                                                                      (.setCommands f.refactor/available-refactors)))
                                        (.setTextDocumentSync (doto (TextDocumentSyncOptions.)
                                                                (.setOpenClose true)
                                                                (.setChange (case (:text-document-sync-kind settings)
                                                                              :full TextDocumentSyncKind/Full
                                                                              :incremental TextDocumentSyncKind/Incremental
                                                                              TextDocumentSyncKind/Full))
                                                                (.setSave (SaveOptions. true))))
                                        (.setCompletionProvider (CompletionOptions. true [":" "/"]))
                                        (.setExperimental {"testTree" true
                                                           "cursorInfo" true
                                                           "serverInfo" true
                                                           "clojuredocs" true})))))))))

  (^void initialized [_ ^InitializedParams _params]
    (start :initialized
           (end
             (do
               (log/info "Initialized!")
               (producer/register-capability
                 (:producer @db/db)
                 (RegistrationParams.
                   [(Registration. "id" "workspace/didChangeWatchedFiles"
                                   (DidChangeWatchedFilesRegistrationOptions.
                                     [(FileSystemWatcher. "**/*.{clj,cljs,cljc,edn}")]))]))))))

  (^CompletableFuture serverInfoRaw [_]
    (CompletableFuture/completedFuture
      (->> (handlers/server-info-raw)
           (coercer/conform-or-log ::coercer/server-info-raw))))

  (^void serverInfoLog [_]
    (start :server-info-log
           (future
             (end
               (handlers/server-info-log)))))

  (^CompletableFuture cursorInfoRaw [_ ^CursorInfoParams params]
    (start :cursorInfoRaw
           (CompletableFuture/completedFuture
             (sync-request params handlers/cursor-info-raw ::coercer/cursor-info-raw))))

  (^void cursorInfoLog [_ ^CursorInfoParams params]
    (start :cursor-info-log
           (future
             (sync-notification params handlers/cursor-info-log))))

  (^CompletableFuture clojuredocsRaw [_ ^ClojuredocsParams params]
    (start :clojuredocsRaw
           (CompletableFuture/completedFuture
             (sync-request params handlers/clojuredocs-raw ::coercer/clojuredocs-raw))))

  (^CompletableFuture shutdown [_]
    (log/info "Shutting down")
    (reset! db/db {:documents {}})
    (CompletableFuture/completedFuture
      {:result nil}))
  (exit [_]
    (log/info "Exitting...")
    (shutdown-agents)
    (System/exit 0))
  (getTextDocumentService [_]
    (LSPTextDocumentService.))
  (getWorkspaceService [_]
    (LSPWorkspaceService.)))

(defn ^:private tee-system-in [^java.io.InputStream system-in]
  (let [buffer-size 1024
        os (java.io.PipedOutputStream.)
        is (java.io.PipedInputStream. os)]
    (thread
      (try
        (let [buffer (byte-array buffer-size)]
          (loop [chs (.read system-in buffer 0 buffer-size)]
            (when (pos? chs)
              (log/warn "FROM STDIN" chs (String. (java.util.Arrays/copyOfRange buffer 0 chs)))
              (.write os buffer 0 chs)
              (recur (.read system-in buffer 0 buffer-size)))))
        (catch Exception e
          (log/error e "in thread"))))
    is))

(defn ^:private tee-system-out [^java.io.OutputStream system-out]
  (let [buffer-size 1024
        is (java.io.PipedInputStream.)
        os (java.io.PipedOutputStream. is)]
    (thread
      (try
        (let [buffer (byte-array buffer-size)]
          (loop [chs (.read is buffer 0 buffer-size)]
            (when (pos? chs)
              (log/warn "FROM STDOUT" chs (String. (java.util.Arrays/copyOfRange buffer 0 chs)))
              (.write system-out buffer)
              (recur (.read is buffer 0 buffer-size)))))
        (catch Exception e
          (log/error e "in thread"))))
    os))

(defrecord LSPProducer [^ClojureLanguageClient client db]
  producer/IProducer

  (publish-diagnostic [_this diagnostic]
    (->> diagnostic
         (coercer/conform-or-log ::coercer/publish-diagnostics-params)
         (.publishDiagnostics client)))

  (refresh-code-lens [_this]
    (when-let [code-lens-capability ^CodeLensWorkspaceCapabilities (get-in @db [:client-capabilities :workspace :code-lens])]
      (when (.getRefreshSupport code-lens-capability)
        (.refreshCodeLenses client))))

  (refresh-test-tree [_this uris]
    (go
      (when (some-> @db/db :client-capabilities :experimental j/from-java :testTree)
        (shared/logging-time
          "Refreshing testTree took %s secs"
          (doseq [uri uris]
            (when-let [test-tree (f.test-tree/tree uri db)]
              (->> test-tree
                   (coercer/conform-or-log ::coercer/publish-test-tree-params)
                   (.publishTestTree client))))))))

  (publish-workspace-edit [_this edit]
    (->> edit
         (coercer/conform-or-log ::coercer/workspace-edit-or-error)
         ApplyWorkspaceEditParams.
         (.applyEdit client)))

  (show-document-request [_this document-request]
    (log/info "Requesting to show on editor the document" document-request)
    (when (.getShowDocument ^WindowClientCapabilities (get-in @db [:client-capabilities :window]))
      (->> (update document-request :range #(or (some-> % shared/->range)
                                                (shared/full-file-range)))
           (coercer/conform-or-log ::coercer/show-document-request)
           (.showDocument client))))

  (publish-progress [_this percentage message progress-token]
    (let [progress (case (int percentage)
                     0 {:kind :begin
                        :title message
                        :percentage percentage}
                     100 {:kind :end
                          :message message
                          :percentage 100}
                     {:kind :report
                      :message message
                      :percentage percentage})]
      (->> {:token (or progress-token "clojure-lsp")
            :value progress}
           (coercer/conform-or-log ::coercer/notify-progress)
           (.notifyProgress client))))

  (show-message-request [_this message type actions]
    (let [result (->> {:type type
                       :message message
                       :actions actions}
                      (coercer/conform-or-log ::coercer/show-message-request)
                      (.showMessageRequest client)
                      .get)]
      (.getTitle ^MessageActionItem result)))

  (show-message [_this message type extra]
    (let [message-content {:message message
                           :type type
                           :extra extra}]
      (log/info message-content)
      (->> message-content
           (coercer/conform-or-log ::coercer/show-message)
           (.showMessage client))))

  (register-capability [_this capability]
    (.registerCapability
      client
      capability)))

(defn run-server! []
  (log/info "Starting server...")
  (let [is (or System/in (tee-system-in System/in))
        os (or System/out (tee-system-out System/out))
        launcher (Launcher/createLauncher (ClojureLSPServer.) ClojureLanguageClient is os)
        debounced-diags (shared/debounce-by db/diagnostics-chan config/diagnostics-debounce-ms :uri)
        debounced-changes (shared/debounce-by db/current-changes-chan config/change-debounce-ms :uri)
        debounced-created-watched-files (shared/debounce-all db/created-watched-files-chan config/created-watched-files-debounce-ms)
        producer (->LSPProducer ^ClojureLanguageClient (.getRemoteProxy launcher) db/db)]
    (nrepl/setup-nrepl db/db)
    (swap! db/db assoc :producer producer)
    (go-loop [edit (<! db/edits-chan)]
      (producer/publish-workspace-edit producer edit)
      (recur (<! db/edits-chan)))
    (go-loop []
      (producer/publish-diagnostic producer (<! debounced-diags))
      (recur))
    (go-loop []
      (try
        (f.file-management/analyze-changes (<! debounced-changes) db/db)
        (catch Exception e
          (log/error e "Error during analyzing buffer file changes")))
      (recur))
    (go-loop []
      (try
        (f.file-management/analyze-watched-created-files! (<! debounced-created-watched-files) db/db)
        (catch Exception e
          (log/error e "Error during analyzing created watched files")))
      (recur))
    (.startListening launcher)))
