(ns xtdb.healthz
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [reitit.http :as http]
            [reitit.http.coercion :as rh.coercion]
            [reitit.http.interceptors.exception :as ri.exception]
            [reitit.http.interceptors.muuntaja :as ri.muuntaja]
            [reitit.interceptor.sieppari :as r.sieppari]
            [reitit.ring :as r.ring]
            [ring.adapter.jetty9 :as j]
            [xtdb.api :as xt]
            [xtdb.node :as xtn]
            [xtdb.protocols :as xtp]
            [xtdb.util :as util])
  (:import io.micrometer.core.instrument.composite.CompositeMeterRegistry
           (io.micrometer.prometheusmetrics PrometheusConfig PrometheusMeterRegistry)
           [java.lang AutoCloseable]
           org.eclipse.jetty.server.Server
           (xtdb.api Xtdb$Config)
           (xtdb.api.metrics HealthzConfig)
           xtdb.api.Xtdb$Config
           xtdb.indexer.IIndexer))

(def router
  (http/router [["/metrics" {:name :metrics
                             :get (fn [{:keys [^PrometheusMeterRegistry prometheus-registry]}]
                                    {:status 200,
                                     :headers {"Content-Type" "text/plain; version=0.0.4"}
                                     :body (.scrape prometheus-registry)})}]

                ["/healthz/started" {:name :started
                                     :get (fn [{:keys [node, ^long initial-target-tx-id]}]
                                            (let [^long lc-tx-id (:tx-id (xtp/latest-completed-tx node) -1)]
                                              (if (< lc-tx-id initial-target-tx-id)
                                                {:status 503,
                                                 :body (format "Catching up - at: %d, target: %d" lc-tx-id initial-target-tx-id)}

                                                {:status 200, :body "Started."})))}]

                ["/healthz/alive" {:name :alive
                                   :get (fn [{:keys [^IIndexer indexer]}]
                                          (if-let [indexer-error (.indexerError indexer)]
                                            {:status 503, :body (str "Indexer error - " indexer-error)}
                                            {:status 200, :body "Alive."}))}]

                ["/healthz/ready" {:name :ready
                                   :get (fn [_] {:status 200, :body "Ready."})}]]

               {:data {:interceptors [[ri.exception/exception-interceptor
                                       (merge ri.exception/default-handlers
                                              {::ri.exception/wrap (fn [handler e req]
                                                                     (log/debug e (format "response error (%s): '%s'" (class e) (ex-message e)))
                                                                     (handler e req))})]

                                      [ri.muuntaja/format-request-interceptor]
                                      [rh.coercion/coerce-request-interceptor]]}}))

(defn- with-opts [opts]
  {:enter (fn [ctx]
            (update ctx :request merge opts))})

(defn handler [opts]
  (http/ring-handler router
                     (r.ring/create-default-handler)
                     {:executor r.sieppari/executor
                      :interceptors [[with-opts opts]]}))

(defmethod xtn/apply-config! :xtdb/healthz [^Xtdb$Config config _ {:keys [^long port]}]
  (.healthz config (HealthzConfig. port)))

(defmethod ig/prep-key :xtdb/healthz [_ ^HealthzConfig config]
  {:port (.getPort config) 
   :metrics-registry (ig/ref :xtdb.metrics/registry)
   :indexer (ig/ref :xtdb/indexer)
   :node (ig/ref :xtdb/node)})

(defmethod ig/init-key :xtdb/healthz [_ {:keys [node, ^long port, ^CompositeMeterRegistry metrics-registry, ^IIndexer indexer]}]
  (let [prometheus-registry (PrometheusMeterRegistry. PrometheusConfig/DEFAULT)
        ^Server server (-> (handler {:prometheus-registry prometheus-registry
                                     :indexer indexer
                                     :initial-target-tx-id (xtp/latest-submitted-tx-id node)})
                           (j/run-jetty {:port port, :async? true, :join? false}))]
    (.add metrics-registry prometheus-registry) 

    (log/info "Healthz server started on port:" port)

    (reify AutoCloseable
      (close [_]
        (.stop server)
        (log/info "Healthz server stopped.")))))

(defmethod ig/halt-key! :xtdb/healthz [_ srv]
  (util/close srv))
