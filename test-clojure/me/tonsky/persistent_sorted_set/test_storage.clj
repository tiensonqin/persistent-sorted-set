(ns me.tonsky.persistent-sorted-set.test-storage
  (:require
    [clojure.string :as str]
    [clojure.test :as t :refer [is are deftest testing]]
    [me.tonsky.persistent-sorted-set :as set])
  (:import
    [clojure.lang RT]
    [java.util Comparator Arrays]
    [me.tonsky.persistent_sorted_set ANode ArrayUtil Branch IStorage Leaf PersistentSortedSet]))

(set! *warn-on-reflection* true)

(defn gen-addr []
  #_(random-uuid)
  (str (str/join (repeatedly 10 #(rand-nth "ABCDEFGHIJKLMNOPQRSTUVWXYZ")))))

(def ^:dynamic *stats*
  nil)

(defn store [^PersistentSortedSet set ^IStorage storage]
  (.store set storage))

(defn restore [^IStorage storage address]
  (set/load RT/DEFAULT_COMPARATOR storage address))

(defrecord Storage [*storage]
  IStorage
  (load [_ address]
    (let [{:keys [keys addresses]} (@*storage address)]
      (ANode/restore (to-array keys) (some-> addresses to-array))))
  (store [_ keys addresses]
    (when *stats*
      (swap! *stats* update :writes inc))
    (let [address (gen-addr)]
      (swap! *storage assoc address
        {:keys      (vec keys)
         :addresses (some-> addresses vec)})
      address)))

(defn make-storage
  ([]
   (->Storage (atom {})))
  ([*storage]
   (->Storage *storage)))

(defn roundtrip [set]
  (let [storage (make-storage)
        address (store set storage)]
    (restore storage address)))

(deftest test-lazy-remove
  "Check that invalidating middle branch does not invalidates siblings"
  (let [size 7000 ;; 3-4 branches
        xs   (shuffle (range size))
        set  (into (set/sorted-set) xs)]
    (store set (make-storage))
    (is (= 1.0 (:durable-ratio (set/stats set)))
      (let [set' (disj set 3500)] ;; one of the middle branches
        (is (< 0.99 (:durable-ratio (set/stats set'))))))))

(defmacro dobatches [[sym coll] & body]
  `(loop [coll# ~coll]
     (when (seq coll#)
       (let [batch# (rand-nth [1 2 3 4 5 10 20 30 40 50 100])
             [~sym tail#] (split-at batch# coll#)]
         ~@body
         (recur tail#)))))

(deftest stresstest-stable-addresses
  (let [size      10000
        adds      (shuffle (range size))
        removes   (shuffle adds)
        *set      (atom (set/sorted-set))
        storage   (make-storage)
        invariant (fn invariant 
                    ([o]
                     (invariant (.-_root ^PersistentSortedSet o) (some? (.-_address ^PersistentSortedSet o))))
                    ([o stored?]
                     (condp instance? o
                       Branch
                       (let [node ^Branch o
                             len (.len node)]
                         (doseq [i (range len)
                                 :let [addr   (nth (.-_addresses node) i)
                                       child  (.child node nil (int i))
                                       {:keys [keys addresses]} (-> storage :*storage deref (get addr))]]
                           ;; nodes inside stored? has to ALL be stored
                           (when stored?
                             (is (some? addr)))
                           (when (some? addr)
                             (is (= keys 
                                   (take (.len ^ANode child) (.-_keys ^ANode child))))
                             (is (= addresses
                                   (when (instance? Branch child)
                                     (take (.len ^Branch child) (.-_addresses ^Branch child))))))
                           (invariant child (some? addr))))
                        
                       Leaf
                       true)))]
    
    (testing "Persist after each"
      (dobatches [xs adds]
        (let [set' (swap! *set into xs)]
          (invariant set')
          (store set' storage)))
      
      (invariant @*set)
      
      (dobatches [xs removes]
        (let [set' (swap! *set #(reduce disj % xs))]
          (invariant set')
          (store set' storage))))
    
    (testing "Persist once"
      (reset! *set (into (set/sorted-set) adds))
      (store @*set storage)
      (dobatches [xs removes]
        (let [set' (swap! *set #(reduce disj % xs))]
          (invariant set'))))))
    
(deftest test-walk
  (let [size    1000000
        xs      (shuffle (range size))
        set     (into (set/sorted-set) xs)
        *stored (atom 0)]
    (set/walk set
      (fn [addr node]
        (is (nil? addr))
        (is (some? node))))
    (store set (make-storage))
    (set/walk set
      (fn [addr node]
        (is (some? addr))
        (swap! *stored inc)
        (is (some? node))))
    (let [set'     (conj set (* 2 size))
          *stored' (atom 0)]
      (set/walk set'
        (fn [addr node]
          (if (some? addr)
            (swap! *stored' inc))
          (is (some? node))))
      (is (= (- @*stored 4) @*stored')))))
    
(deftest test-lazyness
  (let [size     1000000
        xs       (shuffle (range size))
        rm       (vec (repeatedly (quot size 5) #(rand-nth xs)))
        original (-> (reduce disj (into (set/sorted-set) xs) rm)
                   (disj (quot size 4) (quot size 2)))
        *stats   (atom {:writes 0})
        storage  (make-storage)
        address  (binding [*stats* *stats]
                   (store original storage))
        _        (is (> (:writes @*stats) (/ size PersistentSortedSet/MAX_LEN)))
        loaded   (restore storage address)
        _        (is (= 0.0 (:loaded-ratio (set/stats loaded))))
        _        (is (= 1.0 (:durable-ratio (set/stats loaded))))
                
        ; touch first 100
        _       (is (= (take 100 loaded) (take 100 original)))
        l100    (:loaded-ratio (set/stats loaded))
        _       (is (< 0 l100 1.0))
    
        ; touch first 5000
        _       (is (= (take 5000 loaded) (take 5000 original)))
        l5000   (:loaded-ratio (set/stats loaded))
        _       (is (< l100 l5000 1.0))
    
        ; touch middle
        from    (- (quot size 2) (quot size 200))
        to      (+ (quot size 2) (quot size 200))
        _       (is (= (vec (set/slice loaded from to))
                      (vec (set/slice loaded from to))))
        lmiddle (:loaded-ratio (set/stats loaded))
        _       (is (< l5000 lmiddle 1.0))
        
        ; touch 100 last
        _       (is (= (take 100 (rseq loaded)) (take 100 (rseq original))))
        lrseq   (:loaded-ratio (set/stats loaded))
        _       (is (< lmiddle lrseq 1.0))
    
        ; touch 10000 last
        from    (- size (quot size 100))
        to      size
        _       (is (= (vec (set/slice loaded from to))
                      (vec (set/slice loaded from 1000000))))
        ltail   (:loaded-ratio (set/stats loaded))
        _       (is (< lrseq ltail 1.0))
    
        ; conj to beginning
        loaded' (conj loaded -1)
        _       (is (= ltail (:loaded-ratio (set/stats loaded'))))
        _       (is (< (:durable-ratio (set/stats loaded')) 1.0))
        
        ; conj to middle
        loaded' (conj loaded (quot size 2))
        _       (is (= ltail (:loaded-ratio (set/stats loaded'))))
        _       (is (< (:durable-ratio (set/stats loaded')) 1.0))
        
        ; conj to end
        loaded' (conj loaded Long/MAX_VALUE)
        _       (is (= ltail (:loaded-ratio (set/stats loaded'))))
        _       (is (< (:durable-ratio (set/stats loaded')) 1.0))
        
        ; conj to untouched area
        loaded' (conj loaded (quot size 4))
        _       (is (< ltail (:loaded-ratio (set/stats loaded')) 1.0))
        _       (is (< ltail (:loaded-ratio (set/stats loaded)) 1.0))
        _       (is (< (:durable-ratio (set/stats loaded')) 1.0))
    
        ; transients conj
        xs      (range -10000 0)
        loaded' (into loaded xs)
        _       (is (every? loaded' xs))
        lprep   (:loaded-ratio (set/stats loaded'))
        _       (is (< ltail lprep))
        _       (is (< (:durable-ratio (set/stats loaded')) 1.0))
        
        ; incremental persist
        *stats' (atom {:writes 0})
        _       (binding [*stats* *stats']
                  (store loaded' storage))
        _       (is (< (:writes @*stats') 350)) ;; ~ 10000 / 32 + 10000 / 32 / 32 + 1
        _       (is (= lprep (:loaded-ratio (set/stats loaded'))))
        _       (is (= 1.0 (:durable-ratio (set/stats loaded'))))
    
        ; transient disj
        xs      (take 100 loaded)
        loaded' (reduce disj loaded xs)
        _       (is (every? #(not (loaded' %)) xs))
        _       (is (< (:durable-ratio (set/stats loaded')) 1.0))
        
        ; count fetches everything
        _       (is (= (count loaded) (count original)))
        l0      (:loaded-ratio (set/stats loaded))
        _       (is (= 1.0 l0))]))
