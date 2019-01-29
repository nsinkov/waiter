;;
;; Copyright (c) Two Sigma Open Source, LLC
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;  http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
(ns waiter.reporters-test
  (:require [clj-time.core :as t]
            [clojure.test :refer [deftest is]] ;; not using :refer :all because clojure.test has "report" that conflicts with waiter.reporter/report
            [metrics.core :as mc]
            [metrics.counters :as counters]
            [waiter.metrics :as metrics]
            [waiter.reporter :refer :all])
  (:import (clojure.lang ExceptionInfo)
           (com.codahale.metrics MetricFilter MetricRegistry ConsoleReporter)
           (com.codahale.metrics.graphite GraphiteSender)
           (java.io PrintStream ByteArrayOutputStream)))

(def ^:private all-metrics-match-filter (reify MetricFilter (matches [_ _ _] true)))

(defmacro with-isolated-registry
  [& body]
  `(with-redefs [mc/default-registry (MetricRegistry.)]
     (.removeMatching mc/default-registry all-metrics-match-filter)
     (do ~@body)
     (.removeMatching mc/default-registry all-metrics-match-filter)))

(deftest console-reporter-bad-schema
  (is (thrown-with-msg? ExceptionInfo #"period-ms missing-required-key"
                        (validate-console-reporter-config {:extra-key 444}))))

(deftest console-reporter-good-schema
  (validate-console-reporter-config {:extra-key 444 :filter-regex #".*" :period-ms 300}))

(deftest graphite-reporter-bad-schema
  (is (thrown-with-msg? ExceptionInfo #"host missing-required-key"
                        (validate-graphite-reporter-config {:extra-key 444}))))

(deftest graphite-reporter-bad-schema-2
  (is (thrown-with-msg? ExceptionInfo #":port \(not \(pos\?"
                        (validate-graphite-reporter-config {:period-ms 300 :host "localhost" :port -7777}))))

(deftest graphite-reporter-bad-schema-3
  (is (thrown-with-msg? ExceptionInfo #":period-ms \(not \(integer\?"
                        (validate-graphite-reporter-config {:period-ms "five" :host "localhost" :port 7777}))))

(deftest graphite-reporter-good-schema
  (validate-graphite-reporter-config {:extra-key 444 :filter-regex #".*" :period-ms 300 :prefix "" :host "localhost" :port 7777}))

(defn make-printstream []
  (let [os (ByteArrayOutputStream.)
        ps (PrintStream. os)]
    {:ps ps :out #(.toString os "UTF8")}))

(deftest console-reporter-wildcard-filter
  (with-isolated-registry
    (metrics/service-counter "service-id" "foo")
    (metrics/service-counter "service-id" "foo" "bar")
    (metrics/service-counter "service-id" "fee" "fie")
    (counters/inc! (metrics/service-counter "service-id" "foo" "bar") 100)
    (let [{:keys [ps out]} (make-printstream)
          [console-reporter state] (make-console-reporter #".*" ps)]
      (is (instance? ConsoleReporter console-reporter))
      (.report console-reporter)
      (is (= "
-- Counters --------------------------------------------------------------------
services.service-id.counters.fee.fie
             count = 0
services.service-id.counters.foo
             count = 0
services.service-id.counters.foo.bar
             count = 100"
             (->> (out)
                  (clojure.string/split-lines)
                  (drop 1)
                  (clojure.string/join "\n"))))
      (is (= {:run-state :created} @state)))))

(deftest console-reporter-filter
  (with-isolated-registry
    (metrics/service-counter "service-id" "foo")
    (metrics/service-counter "service-id" "foo" "bar")
    (metrics/service-counter "service-id" "fee" "fie")
    (counters/inc! (metrics/service-counter "service-id" "foo" "bar") 100)
    (let [{:keys [ps out]} (make-printstream)
          [console-reporter state] (make-console-reporter #"^.*fee.*" ps)]
      (is (instance? ConsoleReporter console-reporter))
      (.report console-reporter)
      (is (= "
-- Counters --------------------------------------------------------------------
services.service-id.counters.fee.fie
             count = 0"
             (->> (out)
                  (clojure.string/split-lines)
                  (drop 1)
                  (clojure.string/join "\n"))))
      (is (= {:run-state :created} @state)))))

(deftest graphite-reporter-wildcard-filter
  (with-isolated-registry
    (metrics/service-counter "service-id" "foo")
    (metrics/service-counter "service-id" "foo" "bar")
    (metrics/service-counter "service-id" "fee" "fie")
    (counters/inc! (metrics/service-counter "service-id" "foo" "bar") 100)
    (let [actual-values (atom #{})
          time (t/now)
          graphite (reify GraphiteSender
                     (flush [_])
                     (getFailures [_] 0)
                     (isConnected [_] true)
                     (send [_ name value _] (swap! actual-values #(conj % (str name value)))))
          codahale-reporter (make-graphite-reporter 0 #".*" "prefix" graphite)]
      (is (satisfies? CodahaleReporter codahale-reporter))
      (with-redefs [t/now (fn [] time)]
        (report codahale-reporter))
      (is (= #{"prefix.services.service-id.counters.fee.fie0"
               "prefix.services.service-id.counters.foo0"
               "prefix.services.service-id.counters.foo.bar100"}
             @actual-values))
      (is (= {:run-state :created
              :last-reporting-time time
              :failed-writes-to-server 0
              :last-report-successful true} (state codahale-reporter))))))

(deftest graphite-reporter-filter
  (with-isolated-registry
    (metrics/service-counter "service-id" "foo")
    (metrics/service-counter "service-id" "foo" "bar")
    (metrics/service-counter "service-id" "fee" "fie")
    (counters/inc! (metrics/service-counter "service-id" "foo" "bar") 100)
    (let [actual-values (atom #{})
          time (t/now)
          graphite (reify GraphiteSender
                     (flush [_])
                     (getFailures [_] 0)
                     (isConnected [_] true)
                     (send [_ name value _] (swap! actual-values #(conj % (str name value)))))
          codahale-reporter (make-graphite-reporter 0 #"^.*fee.*" "prefix" graphite)]
      (is (satisfies? CodahaleReporter codahale-reporter))
      (with-redefs [t/now (fn [] time)]
        (report codahale-reporter))
      (is (= #{"prefix.services.service-id.counters.fee.fie0"}
             @actual-values))
      (is (= {:run-state :created
              :last-reporting-time time
              :failed-writes-to-server 0
              :last-report-successful true} (state codahale-reporter))))))

(deftest graphite-reporter-wildcard-filter-exception
  (with-isolated-registry
    (metrics/service-counter "service-id" "foo")
    (metrics/service-counter "service-id" "foo" "bar")
    (metrics/service-counter "service-id" "fee" "fie")
    (counters/inc! (metrics/service-counter "service-id" "foo" "bar") 100)
    (let [actual-values (atom #{})
          time (t/now)
          graphite (reify GraphiteSender
                     (flush [_])
                     (getFailures [_] 0)
                     (isConnected [_] true)
                     (send [_ _ _ _] (throw (ex-info "test" {}))))
          codahale-reporter (make-graphite-reporter 0 #".*" "prefix" graphite)]
      (is (satisfies? CodahaleReporter codahale-reporter))
      (with-redefs [t/now (fn [] time)]
        (is (thrown-with-msg? ExceptionInfo #"^test$"
                              (report codahale-reporter))))
      (is (= #{} @actual-values))
      (is (= {:run-state :created
              :last-send-failed-time time
              :failed-writes-to-server 0
              :last-report-successful false} (state codahale-reporter))))))