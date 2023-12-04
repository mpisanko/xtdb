(ns xtdb.jackson
  (:require [xtdb.error :as err]
            [jsonista.core :as json]
            [jsonista.tagged :as jt])
  (:import (clojure.lang Keyword PersistentHashSet)
           (com.fasterxml.jackson.core JsonGenerator)
           (com.fasterxml.jackson.databind ObjectMapper)
           (com.fasterxml.jackson.databind.module SimpleModule)
           (java.time Instant Duration LocalDate LocalDateTime ZonedDateTime)
           (java.util Date Map)
           (jsonista.jackson FunctionalSerializer)
           (xtdb.jackson JsonLdValueOrPersistentHashMapDeserializer)))

(defn serializer ^FunctionalSerializer [^String tag encoder]
  (FunctionalSerializer.
   (fn [value ^JsonGenerator gen]
     (.writeStartObject gen)
     (.writeStringField gen "@type" tag)
     (.writeFieldName gen "@value")
     (encoder value gen)
     (.writeEndObject gen))))

(defn encode-throwable [^Throwable t ^JsonGenerator gen]
  (let [mapper ^ObjectMapper (.getCodec gen)]
    (.writeStartObject gen)
    (.writeStringField gen "xtdb.error/message" (.getMessage t))
    (.writeStringField gen "xtdb.error/class" (.getName (.getClass t)))
    (.writeFieldName gen "xtdb.error/data")
    (.writeRawValue gen (.writeValueAsString mapper (ex-data t)))
    (.writeEndObject gen)))

;; TODO this only works in connection with keyword decoding
(defn decode-throwable [{:xtdb.error/keys [message class data] :as m}]
  (case class
    "xtdb.IllegalArgumentException" (err/illegal-arg (:xtdb.error/error-key data) data)
    "xtdb.RuntimeException" (err/runtime-err (:xtdb.error/error-key data) data)
    (ex-info message data)))

(defn json-ld-module
  "See jsonista.tagged/module but for Json-Ld reading/writing."
  ^SimpleModule
  [{:keys [handlers]}]
  (let [decoders (->> (for [[_ {:keys [tag decode]}] handlers] [tag decode]) (into {}))]
    (reduce-kv
     (fn [^SimpleModule module t {:keys [tag encode] :or {encode jt/encode-str}}]
       (.addSerializer module t (serializer tag encode)))
     (doto (SimpleModule. "JSON-LD")
       (.addDeserializer Map (JsonLdValueOrPersistentHashMapDeserializer. decoders)))
     handlers)))

(def handlers {Keyword {:tag "xt:keyword"
                        :encode jt/encode-keyword
                        :decode keyword}
               PersistentHashSet {:tag "xt:set"
                                  :encode jt/encode-collection
                                  :decode set}
               Date {:tag "xt:timestamp"
                     :encode #(str (.toInstant ^Date %))
                     :decode #(LocalDateTime/parse %)}
               LocalDate {:tag "xt:date"
                          :decode #(LocalDate/parse %)}
               Duration {:tag "xt:duration"
                         :decode #(Duration/parse %)}
               LocalDateTime {:tag "xt:timestamp"
                              :decode #(LocalDateTime/parse %)}
               ZonedDateTime {:tag "xt:timestamptz"
                              :decode #(ZonedDateTime/parse %)}
               Instant {:tag "xt:timestamp"
                        :decode #(LocalDateTime/parse %)}
               Throwable {:tag "xt:error"
                          :encode encode-throwable
                          :decode decode-throwable}})

(comment
  (def mapper
    (json/object-mapper
     {:encode-key-fn true
      :decode-key-fn true
      :modules [(json-ld-module {:handlers handlers})]}))

  (-> (json/write-value-as-string {:foo :bar :toto #{:foo :toto}} mapper)
      (json/read-value mapper))

  (-> (json/write-value-as-string (err/illegal-arg :divison-by-zero {:foo "bar"}) mapper)
      (json/read-value mapper)))