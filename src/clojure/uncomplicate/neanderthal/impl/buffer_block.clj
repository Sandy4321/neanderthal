;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns uncomplicate.neanderthal.impl.buffer-block
  (:require [vertigo
             [core :refer [wrap]]
             [bytes :refer [direct-buffer byte-seq slice-buffer]]
             [structs :refer [float64 float32 wrap-byte-seq]]]
            [uncomplicate.fluokitten.protocols
             :refer [PseudoFunctor Functor Foldable Magma Monoid Applicative]]
            [uncomplicate.neanderthal
             [protocols :refer :all]
             [core :refer [transfer! copy!]]
             [real :refer [entry]]]
            [uncomplicate.neanderthal.impl.fluokitten :refer :all]
            [uncomplicate.commons.core :refer [Releaseable release let-release]])
  (:import [java.nio ByteBuffer DirectByteBuffer]
           [clojure.lang Seqable IFn IFn$DD IFn$DDD IFn$DDDD IFn$DDDDD IFn$LD IFn$LLD IFn$L]
           [vertigo.bytes ByteSeq]
           [uncomplicate.neanderthal.protocols BLAS BLASPlus RealBufferAccessor BufferAccessor
            DataAccessor Block Vector Matrix RealVector RealMatrix GEMatrix TRMatrix  RealChangeable
            RealOrderNavigator UploNavigator StripeNavigator]))

(defn ^:private hash* ^double [^double h ^double x]
  (double (clojure.lang.Util/hashCombine h (Double/hashCode x))))

;; ============ Realeaseable ===================================================

(defn ^:private clean-buffer [^ByteBuffer buffer]
  (do
    (if (.isDirect buffer)
      (.clean (.cleaner ^DirectByteBuffer buffer)))
    true))

(extend-type DirectByteBuffer
  Releaseable
  (release [this]
    (clean-buffer this)))

(extend-type clojure.lang.Sequential
  Releaseable
  (release [this]
    true)
  Container
  (raw [this fact]
    (let [n (count this)]
      (create-vector fact n false))))

(deftype ColumnRealOrderNavigator []
  RealOrderNavigator
  (sd [_ m n]
    m)
  (fd [_ m n]
    n)
  (index [_ offset ld i j]
    (+ offset (* ld j) i))
  (get [_ a i j]
    (.entry a i j))
  (set [_ a i j val]
    (.set a i j val))
  (stripe [_ a j]
    (.col a j)))

(deftype RowRealOrderNavigator []
  RealOrderNavigator
  (sd [_ m n]
    n)
  (fd [_ m n]
    m)
  (index [_ offset ld i j]
    (+ offset (* ld i) j))
  (get [_ a i j]
    (.entry a j i))
  (set [_ a i j val]
    (.set a j i val))
  (stripe [_ a i]
    (.row a i)))

(def col-navigator (ColumnRealOrderNavigator.))
(def row-navigator (RowRealOrderNavigator.))

(deftype NonUnitLowerNavigator []
  UploNavigator
  (colStart [_ _ i]
    i)
  (colEnd [_ n _]
    n)
  (rowStart [_ _ _]
    0)
  (rowEnd [_ n i]
    (inc i))
  (defaultEntry [_ i j]
    (* 2 (Long/signum (inc (Long/signum (- i j)))))))

(deftype UnitLowerNavigator []
  UploNavigator
  (colStart [_ _ i]
    (inc i))
  (colEnd [_ n _]
    n)
  (rowStart [_ _ _]
    0)
  (rowEnd [_ _ i]
    i)
  (defaultEntry [_ i j]
    (inc (Long/signum (- i j)))))

(deftype NonUnitUpperNavigator []
  UploNavigator
  (colStart [_ _ _]
    0)
  (colEnd [_ _ i]
    (inc i))
  (rowStart [_ _ i]
    i)
  (rowEnd [_ n _]
    n)
  (defaultEntry [_ i j]
    (* 2 (Long/signum (inc (Long/signum (- j i)))))))

(deftype UnitUpperNavigator []
  UploNavigator
  (colStart [_ _ _]
    0)
  (colEnd [_ _ i]
    i)
  (rowStart [_ _ i]
    (inc i))
  (rowEnd [_ n _]
    n)
  (defaultEntry [_ i j]
    (inc (Long/signum (- j i)))))

(def non-unit-upper-nav (NonUnitUpperNavigator.))
(def unit-upper-nav (UnitUpperNavigator.))
(def non-unit-lower-nav (NonUnitLowerNavigator.))
(def unit-lower-nav (UnitLowerNavigator.))

(deftype UnitTopNavigator []
  StripeNavigator
  (start [_ _ _]
    0)
  (end [_ _ i]
    i))

(deftype NonUnitTopNavigator []
  StripeNavigator
  (start [_ _ i]
    0)
  (end [_ _ i]
    (inc i)))

(deftype UnitBottomNavigator []
  StripeNavigator
  (start [_ _ i]
    (inc i))
  (end [_ n _]
    n))

(deftype NonUnitBottomNavigator []
  StripeNavigator
  (start [_ _ i]
    i)
  (end [_ n _]
    n))

(def non-unit-top-navigator (NonUnitTopNavigator.))
(def unit-top-navigator (UnitTopNavigator.))
(def non-unit-bottom-navigator (NonUnitBottomNavigator.))
(def unit-bottom-navigator (UnitBottomNavigator.))

;; ================== Declarations ============================================

(declare real-block-vector)
(declare real-ge-matrix)
(declare real-tr-matrix)

;; ============ Real Buffer ====================================================

(deftype FloatBufferAccessor []
  DataAccessor
  (entryType [_]
    Float/TYPE)
  (entryWidth [_]
    Float/BYTES)
  (count [_ b]
    (quot (.capacity ^ByteBuffer b) Float/BYTES))
  (createDataSource [_ n]
    (direct-buffer (* Float/BYTES n)))
  (initialize [_ b]
    b)
  (initialize [this b v]
    (let [v (double v)
          strd Float/BYTES]
      (dotimes [i (.count this b)]
        (.putFloat ^ByteBuffer b (* strd i) v))
      b))
  DataAccessorProvider
  (data-accessor [this]
    this)
  MemoryContext
  (compatible? [this o]
    (let [da (data-accessor o)]
      (or (identical? this da) (instance? FloatBufferAccessor da))))
  BufferAccessor
  (toSeq [this buf offset stride]
    (if (< offset (.count this buf))
      (wrap-byte-seq float32 (* Float/BYTES stride) (* Float/BYTES offset) (byte-seq buf))
      (list)))
  (slice [_ buf k l]
    (slice-buffer buf (* Float/BYTES k) (* Float/BYTES l)))
  RealBufferAccessor
  (get [_ buf i]
    (.getFloat buf (* Float/BYTES i)))
  (set [_ buf i val]
    (.putFloat buf (* Float/BYTES i) val)))

(def float-accessor (->FloatBufferAccessor))

(deftype DoubleBufferAccessor []
  DataAccessor
  (entryType [_]
    Double/TYPE)
  (entryWidth [_]
    Double/BYTES)
  (count [_ b]
    (quot (.capacity ^ByteBuffer b) Double/BYTES))
  (createDataSource [_ n]
    (direct-buffer (* Double/BYTES n)))
  (initialize [_ b]
    b)
  (initialize [this b v]
    (let [v (double v)
          strd Double/BYTES]
      (dotimes [i (.count this b)]
        (.putDouble ^ByteBuffer b (* strd i) v))
      b))
  DataAccessorProvider
  (data-accessor [this]
    this)
  MemoryContext
  (compatible? [this o]
    (let [da (data-accessor o)]
      (or (identical? this da) (instance? DoubleBufferAccessor da))))
  BufferAccessor
  (toSeq [this buf offset stride]
    (if (< offset (.count this buf))
      (wrap-byte-seq float64 (* Double/BYTES stride) (* Double/BYTES offset) (byte-seq buf))
      (list)))
  (slice [_ buf k l]
    (slice-buffer buf (* Double/BYTES k) (* Double/BYTES l)))
  RealBufferAccessor
  (get [_ buf i]
    (.getDouble buf (* Double/BYTES i)))
  (set [_ buf i val]
    (.putDouble buf (* Double/BYTES i) val)))

(def double-accessor (->DoubleBufferAccessor))

;; ============ Real Vector ====================================================

(deftype RealBlockVector [^uncomplicate.neanderthal.protocols.Factory fact ^RealBufferAccessor da
                          ^BLASPlus eng ^Boolean master ^ByteBuffer buf ^long n ^long ofst ^long strd]
  Object
  (hashCode [x]
    (-> (hash :RealBlockVector) (hash-combine n) (hash-combine (.nrm2 eng x))))
  (equals [x y]
    (cond
      (nil? y) false
      (identical? x y) true
      (and (instance? RealBlockVector y) (compatible? da y) (fits? x y))
      (loop [i 0]
        (if (< i n)
          (and (= (.entry x i) (.entry ^RealBlockVector y i)) (recur (inc i)))
          true))
      :default false))
  (toString [_]
    (format "#RealBlockVector[%s, n:%d, offset: %d, stride:%d]" (.entryType da) n ofst strd))
  Releaseable
  (release [_]
    (if master (clean-buffer buf) true))
  Seqable
  (seq [_]
    (take n (.toSeq da buf ofst strd)))
  Container
  (raw [_]
    (real-block-vector fact n))
  (raw [_ fact]
    (create-vector fact n false))
  (zero [_]
    (real-block-vector fact n))
  (zero [_ fact]
    (create-vector fact n true))
  (host [x]
    (let-release [res (raw x)]
      (.copy eng x res)))
  (native [x]
    x)
  MemoryContext
  (compatible? [_ y]
    (compatible? da y))
  (fits? [_ y]
    (= n (.dim ^Vector y)))
  EngineProvider
  (engine [_]
    eng)
  FactoryProvider
  (factory [_]
    fact)
  (native-factory [_]
    fact)
  DataAccessorProvider
  (data-accessor [_]
    da)
  Block
  (buffer [_]
    buf)
  (offset [_]
    ofst)
  (stride [_]
    strd)
  (count [_]
    n)
  IFn$LD
  (invokePrim [x i]
    (.entry x i))
  IFn$L
  (invokePrim [x]
    n)
  IFn
  (invoke [x i]
    (.entry x i))
  (invoke [x]
    n)
  RealChangeable
  (set [x val]
    (.set eng val x)
    x)
  (set [x i val]
    (.set da buf (+ ofst (* strd i)) val)
    x)
  (setBoxed [x val]
    (.set x val))
  (setBoxed [x i val]
    (.set x i val))
  (alter [x i f]
    (.set x i (.invokePrim ^IFn$DD f (.entry x i))))
  RealVector
  (dim [_]
    n)
  (entry [_ i]
    (.get da buf (+ ofst (* strd i))))
  (boxedEntry [x i]
    (.entry x i))
  (subvector [_ k l]
    (real-block-vector fact false buf l (+ ofst (* k strd)) strd))
  Monoid
  (id [x]
    (real-block-vector fact 0))
  PseudoFunctor
  (fmap! [x f]
    (vector-fmap ^IFn$DD f x))
  (fmap! [x f y]
    (vector-fmap ^IFn$DDD f x ^RealVector y))
  (fmap! [x f y z]
    (vector-fmap ^IFn$DDDD f x ^RealVector y ^RealVector z))
  (fmap! [x f y z v]
    (vector-fmap ^IFn$DDDDD f x ^RealVector y ^RealVector z ^RealVector v))
  (fmap! [x f y z v ws]
    (vector-fmap f x y z v ws))
  Foldable
  (fold [x]
    (.sum eng x))
  (fold [x f init]
    (vector-fold f init x))
  (fold [x f init y]
    (vector-fold f init x ^RealVector y))
  (fold [x f init y z]
    (vector-fold f init x ^RealVector y ^RealVector z))
  (fold [x f init y z v]
    (vector-fold f init x ^RealVector y ^RealVector z ^RealVector v))
  (fold [x f init y z v ws]
    (vector-fold f init x y z v ws))
  (foldmap [x g]
    (loop [i 0 acc 0.0]
      (if (< i n)
        (recur (inc i) (+ acc (.invokePrim ^IFn$DD g (.entry x i))))
        acc)))
  (foldmap [x g f init]
    (vector-foldmap f init ^IFn$DD g x))
  (foldmap [x g f init y]
    (vector-foldmap f init ^IFn$DDD g x ^RealVector y))
  (foldmap [x g f init y z]
    (vector-foldmap f int ^IFn$DDDD g x ^RealVector y ^RealVector z))
  (foldmap [x g f init y z v]
    (vector-foldmap f init ^IFn$DDDDD g x ^RealVector y ^RealVector z ^RealVector v))
  (foldmap [x g f init y z v ws]
    (vector-foldmap f init g x y z v ws)))()

(defn real-block-vector
  ([fact master ^ByteBuffer buf n ofst strd]
   (RealBlockVector. fact (data-accessor fact) (vector-engine fact) master buf n ofst strd))
  ([fact n]
   (let-release [buf (.createDataSource (data-accessor fact) n)]
     (real-block-vector fact true buf n 0 1))))

(extend RealBlockVector
  Functor
  {:fmap copy-fmap}
  Applicative
  {:pure vector-pure}
  Magma
  {:op (constantly vector-op)})

(defmethod print-method RealBlockVector
  [^Vector x ^java.io.Writer w]
  (.write w (format "%s%s" (str x) (pr-str (take 100 (seq x))))))

(defmethod transfer! [RealBlockVector RealBlockVector]
  [source destination]
  (copy! source destination))

(defmethod transfer! [clojure.lang.Sequential RealBlockVector]
  [source ^RealBlockVector destination]
  (let [n (.dim destination)]
    (loop [i 0 src source]
      (if (and src (< i n))
        (do
          (.set destination i (first src))
          (recur (inc i) (next src)))
        destination))))

;; =================== Real Matrix =============================================

(deftype RealGEMatrix [^RealOrderNavigator navigator ^uncomplicate.neanderthal.protocols.Factory fact
                       ^RealBufferAccessor da ^BLASPlus eng ^Boolean master ^ByteBuffer buf ^long m ^long n
                       ^long ofst ^long ld ^long sd ^long fd ^long ord]
  Object
  (hashCode [a]
    (-> (hash :RealGEMatrix) (hash-combine m) (hash-combine n)
        (hash-combine (.nrm2 eng (.stripe navigator a 0)))))
  (equals [a b]
    (cond
      (nil? b) false
      (identical? a b) true
      (and (instance? RealGEMatrix b) (compatible? da b) (fits? a b))
      (loop [j 0]
        (if (< j fd)
          (and (loop [i 0]
                 (if (< i sd)
                   (and (= (.get da buf (+ ofst (* ld j) i)) (.get navigator b i j))
                        (recur (inc i)))
                   true))
               (recur (inc j)))
          true))
      :default false))
  (toString [a]
    (format "#RealGEMatrix[%s, mxn:%dx%d, order%s, offset:%d, ld:%d]"
            (.entryType da) m n (dec-property ord) ofst ld))
  Releaseable
  (release [_]
    (if master (clean-buffer buf) true))
  EngineProvider
  (engine [_]
    eng)
  FactoryProvider
  (factory [_]
    fact)
  (native-factory [_]
    fact)
  DataAccessorProvider
  (data-accessor [_]
    da)
  Seqable
  (seq [a]
    (map #(seq (.stripe navigator a %)) (range 0 fd)))
  Container
  (raw [_]
    (real-ge-matrix fact m n ord))
  (raw [_ fact]
    (create-ge fact m n ord false))
  (zero [_]
    (real-ge-matrix fact m n ord))
  (zero [_ fact]
    (create-ge fact m n ord true))
  (host [a]
    (let-release [res (raw a)]
      (.copy eng a res)))
  (native [a]
    a)
  DenseContainer
  (subtriangle [_ uplo diag];;TODO remove and introduce new function similar to copy that reuses memory (view x :tr)
    (real-tr-matrix fact false buf (min m n) 0 ld ord uplo diag))
  MemoryContext
  (compatible? [_ b]
    (compatible? da b))
  (fits? [_ b]
    (and (= m (.mrows ^GEMatrix b)) (= n (.ncols ^GEMatrix b))))
  GEMatrix
  (buffer [_]
    buf)
  (offset [_]
    ofst)
  (stride [_]
    ld)
  (order [_]
    ord)
  (count [_]
    (* m n))
  (sd [_]
    sd)
  (fd [_]
    fd)
  IFn$LLD
  (invokePrim [a i j]
    (entry a i j))
  IFn
  (invoke [a i j]
    (entry a i j))
  (invoke [a]
    sd)
  IFn$L
  (invokePrim [a]
    sd)
  RealChangeable
  (isAllowed [a i j]
    true)
  (set [a val]
    (.set eng val a)
    a)
  (set [a i j val]
    (.set da buf (.index navigator ofst ld i j) val)
    a)
  (setBoxed [a val]
    (.set a val))
  (setBoxed [a i j val]
    (.set a i j val))
  (alter [a i j f]
    (.set a i j (.invokePrim ^IFn$DD f (.entry a i j))))
  RealMatrix
  (mrows [_]
    m)
  (ncols [_]
    n)
  (entry [a i j]
    (.get da buf (.index navigator ofst ld i j)))
  (boxedEntry [a i j]
    (.entry a i j))
  (row [a i]
    (real-block-vector fact false buf n (.index navigator ofst ld i 0) (if (= ROW_MAJOR ord) 1 ld)))
  (col [a j]
    (real-block-vector fact false buf m (.index navigator ofst ld 0 j) (if (= COLUMN_MAJOR ord) 1 ld)))
  (submatrix [a i j k l]
    (real-ge-matrix fact false buf k l (.index navigator ofst ld i j) ld ord))
  (transpose [a]
    (real-ge-matrix fact false buf n m ofst ld (if (= COLUMN_MAJOR ord) ROW_MAJOR COLUMN_MAJOR)))
  Monoid
  (id [a]
    (real-ge-matrix fact 0 0))
  PseudoFunctor
  (fmap! [a f]
    (ge-fmap navigator fd sd ^IFn$DD f a))
  (fmap! [a f b]
    (ge-fmap navigator fd sd ^IFn$DDD f a b))
  (fmap! [a f b c]
    (ge-fmap navigator fd sd ^IFn$DDDD f a b c))
  (fmap! [a f b c d]
    (ge-fmap navigator fd sd ^IFn$DDDDD f a b c d))
  (fmap! [a f b c d es]
    (ge-fmap navigator fd sd f a b c d es))
  Foldable
  (fold [_]
    (loop [j 0 acc 0.0]
      (if (< j fd)
        (recur (inc j)
               (double
                (loop [i 0 acc acc]
                  (if (< i sd)
                    (recur (inc i) (+ acc (.get da buf (+ ofst (* ld j) i))))
                    acc))))
        acc)))
  (fold [a f init]
    (matrix-fold navigator fd f init a))
  (fold [a f init b]
    (matrix-fold navigator fd f init a b))
  (fold [a f init b c]
    (matrix-fold navigator fd f init a b c))
  (fold [a f init b c d]
    (matrix-fold navigator fd f init a b c d))
  (fold [a f init b c d es]
    (matrix-fold navigator fd f init a b c d es))
  (foldmap [_ g]
    (loop [j 0 acc 0.0]
      (if (< j fd)
        (recur (inc j)
               (double
                (loop [i 0 acc acc]
                  (if (< i sd)
                    (recur (inc i)
                           (+ acc (.invokePrim ^IFn$DD g (.get da buf (+ ofst (* ld j) i)))))
                    acc))))
        acc)))
  (foldmap [a g f init]
    (matrix-foldmap navigator fd f init ^IFn$DD g a))
  (foldmap [a g f init b]
    (matrix-foldmap navigator fd f init ^IFn$DDD g a b))
  (foldmap [a g f init b c]
    (matrix-foldmap navigator fd f init ^IFn$DDDD g a b c))
  (foldmap [a g f init b c d]
    (matrix-foldmap navigator fd f init ^IFn$DDDDD g a b c d))
  (foldmap [a g f init b c d es]
    (matrix-foldmap navigator fd f init g a b c d es)))

(defn real-ge-matrix
  ([fact master buf m n ofst ld ord]
   (let [^RealOrderNavigator navigator (if (= COLUMN_MAJOR ord) col-navigator row-navigator)]
     (RealGEMatrix. (if (= COLUMN_MAJOR ord) col-navigator row-navigator) fact (data-accessor fact)
                    (ge-engine fact) master buf m n ofst (max (long ld) (.sd navigator m n))
                    (.sd navigator m n) (.fd navigator m n) ord)))
  ([fact ^long m ^long n ord]
   (let-release [buf (.createDataSource (data-accessor fact) (* m n))]
     (real-ge-matrix fact true buf m n 0 m ord)))
  ([fact ^long m ^long n]
   (real-ge-matrix fact m n DEFAULT_ORDER)))

(extend RealGEMatrix
  Functor
  {:fmap copy-fmap}
  Applicative
  {:pure matrix-pure}
  Magma
  {:op (constantly matrix-op)})

(defmethod print-method RealGEMatrix
  [^RealGEMatrix a ^java.io.Writer w]
  (.write w (format "%s%s" (str a) (pr-str (take 100 (seq a))))))

(defmethod transfer! [RealGEMatrix RealGEMatrix]
  [source destination]
  (copy! source destination))

(defmethod transfer! [clojure.lang.Sequential RealGEMatrix]
  [source ^RealGEMatrix destination]
  (let [ofst (.offset destination)
        ld (.ld destination)
        sd (.sd destination)
        fd (.fd destination)
        da (data-accessor destination)
        buf (.buffer destination)]
    (loop [j 0 src source]
      (if (and src (< j fd))
        (recur (inc j)
               (loop [i 0 src src]
                 (if (and src (< i sd))
                   (do (.set ^RealBufferAccessor da buf (+ ofst (* ld j) i) (first src))
                       (recur (inc i) (next src)))
                   src)))
        destination))))

;; =================== Real Triangular Matrix ==================================

(deftype RealTRMatrix [^RealOrderNavigator navigator ^UploNavigator uplo-nav ^StripeNavigator stripe-nav
                       ^uncomplicate.neanderthal.protocols.Factory fact ^RealBufferAccessor da
                       ^BLASPlus eng ^Boolean master ^ByteBuffer buf ^long n ^long ofst ^long ld
                       ^long ord ^long fuplo ^long fdiag]
  Object
  (hashCode [a]
    (-> (hash :RealTRMatrix) (hash-combine n) (hash-combine (.nrm2 eng (.stripe navigator a 0)))))
  (equals [a b]
    (cond
      (nil? b) false
      (identical? a b) true
      (and (instance? RealTRMatrix b) (compatible? da b) (fits? a b))
      (let [buf-b (.buf ^RealTRMatrix b)
            ofst-b (.ofst ^RealTRMatrix b)
            ld-b (.ld ^RealTRMatrix b)]
        (loop [j 0]
          (if (< j n)
            (let [end (.end stripe-nav n j)]
              (and (loop [i (.start stripe-nav n j)]
                     (if (< i end)
                       (and (= (.get da buf (+ ofst (* ld j) i))
                               (.get da buf-b (.index navigator ofst-b ld-b i j)))
                            (recur (inc i)))
                       true))
                   (recur (inc j))))
            true)))
      :default false))
  (toString [a]
    (format "#RealTRMatrix[%s, mxn:%dx%d, order%s, uplo%s, diag%s, offset:%d, ld:%d]"
            (.entryType da) n n (dec-property ord) (dec-property fuplo) (dec-property fdiag) ofst ld ))
  Releaseable
  (release [_]
    (if master (clean-buffer buf) true))
  EngineProvider
  (engine [_]
    eng)
  FactoryProvider
  (factory [_]
    fact)
  (native-factory [_]
    fact)
  DataAccessorProvider
  (data-accessor [_]
    da)
  Container
  (raw [_]
    (real-tr-matrix fact n ord fuplo fdiag))
  (raw [_ fact]
    (create-tr fact n ord fuplo fdiag false))
  (zero [a]
    (raw a))
  (zero [_ fact]
    (create-tr fact n ord fuplo fdiag true))
  (host [a]
    (let-release [res (raw a)]
      (.copy eng a res)))
  (native [a]
    a)
  MemoryContext
  (compatible? [_ b]
    (compatible? da b))
  (fits? [_ b]
    (and (= n (.mrows ^TRMatrix b)) (= fuplo (.uplo ^TRMatrix b)) (= fdiag (.diag ^TRMatrix b))))
  Monoid
  (id [a]
    (real-tr-matrix fact 0))
  TRMatrix
  (buffer [_]
    buf)
  (offset [_]
    ofst)
  (stride [_]
    ld)
  (count [_]
    (* n n))
  (uplo [_]
    fuplo)
  (diag [_]
    fdiag)
  (order [_]
    ord)
  (sd [_]
    n)
  (fd [_]
    n)
  Seqable
  (seq [a]
    (map #(seq (.stripe navigator a %)) (range 0 n)))
  IFn$LLD
  (invokePrim [a i j]
    (entry a i j))
  IFn
  (invoke [a i j]
    (entry a i j))
  (invoke [a]
    n)
  IFn$L
  (invokePrim [a]
    n)
  RealChangeable
  (isAllowed [a i j]
    (= 2 (.defaultEntry uplo-nav i j)))
  (set [a val]
    (.set eng val a)
    a)
  (set [a i j val]
    (.set da buf (.index navigator ofst ld i j) val)
    a)
  (setBoxed [a val]
    (.set a val))
  (setBoxed [a i j val]
    (.set a i j val))
  (alter [a i j f]
    (.set a i j (.invokePrim ^IFn$DD f (.entry a i j))))
  RealMatrix
  (mrows [_]
    n)
  (ncols [_]
    n)
  (entry [a i j]
    (let [res (.defaultEntry uplo-nav i j)]
      (if (= 2 res)
        (.get da buf (.index navigator ofst ld i j))
        res)))
  (boxedEntry [a i j]
    (.entry a i j))
  (row [a i]
    (let [start (.rowStart uplo-nav n i)]
      (real-block-vector fact false buf (- (.rowEnd uplo-nav n i) start)
                         (.index navigator ofst ld i start) (if (= ROW_MAJOR ord) 1 ld))))
  (col [a j]
    (let [start (.colStart uplo-nav n j)]
      (real-block-vector fact false buf (- (.colEnd uplo-nav n j) start)
                         (.index navigator ofst ld start j) (if (= COLUMN_MAJOR ord) 1 ld))))
  (submatrix [a i j k l]
    (if (and (= i j) (= k l))
      (real-tr-matrix fact false buf k (.index navigator ofst ld i j) ld ord fuplo fdiag))
    (throw (UnsupportedOperationException. "Submatrix of a TR matrix has to be triangular.")))
  (transpose [a]
    (real-tr-matrix fact false buf n ofst ld (if (= COLUMN_MAJOR ord) ROW_MAJOR COLUMN_MAJOR)
                    (if (= LOWER fuplo) UPPER LOWER) fdiag))
  PseudoFunctor
  (fmap! [a f]
    (tr-fmap navigator stripe-nav n ^IFn$DD f a))
  (fmap! [a f b]
    (tr-fmap navigator stripe-nav n ^IFn$DDD f a b))
  (fmap! [a f b c]
    (tr-fmap navigator stripe-nav n ^IFn$DDDD f a b c))
  (fmap! [a f b c d]
    (tr-fmap navigator stripe-nav n ^IFn$DDDDD f a b c d))
  (fmap! [a f b c d es]
    (tr-fmap navigator stripe-nav n f a b c d nil))
  Foldable
  (fold [_]
    (loop [j 0 acc 0.0]
      (if (< j n)
        (recur (inc j)
               (double
                (let [end (.end stripe-nav n j)]
                  (loop [i (.start stripe-nav n j) acc acc]
                    (if (< i end)
                      (recur (inc i) (+ acc (.get da buf (+ ofst (* ld j) i))))
                      acc)))))
        acc)))
  (fold [a f init]
    (matrix-fold navigator n f init a))
  (fold [a f init b]
    (matrix-fold navigator n f init a b))
  (fold [a f init b c]
    (matrix-fold navigator n f init a b c))
  (fold [a f init b c d]
    (matrix-fold navigator n f init a b c d))
  (fold [a f init b c d es]
    (matrix-fold navigator n f init a b c d es))
  (foldmap [_ g]
    (loop [j 0 acc 0.0]
      (if (< j n)
        (recur (inc j)
               (double
                (let [end (.end stripe-nav n j)]
                  (loop [i (.start stripe-nav n j) acc acc]
                    (if (< i end)
                      (recur (inc i) (.invokePrim ^IFn$DD g (.get da buf (+ ofst (* ld j) i))))
                      acc)))))
        acc)))
  (foldmap [a g f init]
    (matrix-foldmap navigator n f init ^IFn$DD g a))
  (foldmap [a g f init b]
    (matrix-foldmap navigator n f init ^IFn$DDD g a b))
  (foldmap [a g f init b c]
    (matrix-foldmap navigator n f init ^IFn$DDDD g a b c))
  (foldmap [a g f init b c d]
    (matrix-foldmap navigator n f init ^IFn$DDDDD g a b c d))
  (foldmap [a g f init b c d es]
    (matrix-foldmap navigator n f init g a b c d es)))

(extend RealTRMatrix
  Functor
  {:fmap copy-fmap}
  Applicative
  {:pure matrix-pure}
  Magma
  {:op (constantly matrix-op)})

(defn real-tr-matrix
  ([fact master ^ByteBuffer buf n ofst ld ord uplo diag]
   (let [unit (= DIAG_UNIT diag)
         lower (= LOWER uplo)
         column (= COLUMN_MAJOR ord)
         bottom (if lower column (not column))
         order-nav (if column col-navigator row-navigator)
         uplo-nav (if lower
                    (if unit unit-lower-nav non-unit-lower-nav)
                    (if unit unit-upper-nav non-unit-upper-nav))
         stripe-nav (if bottom
                      (if unit unit-bottom-navigator non-unit-bottom-navigator)
                      (if unit unit-top-navigator non-unit-top-navigator))]
     (RealTRMatrix. order-nav uplo-nav stripe-nav fact (data-accessor fact) (tr-engine fact)
                    master buf n ofst (max (long ld) (long n)) ord uplo diag)))
  ([fact n ord uplo diag]
   (let-release [buf (.createDataSource (data-accessor fact) (* (long n) (long n)))]
     (real-tr-matrix fact true buf n 0 n ord uplo diag)))
  ([fact n]
   (real-tr-matrix fact n DEFAULT_ORDER DEFAULT_UPLO DEFAULT_DIAG)))

(defmethod print-method RealTRMatrix
  [^RealTRMatrix a ^java.io.Writer w]
  (.write w (format "%s%s" (str a) (pr-str (take 100 (seq a))))))

(defmethod transfer! [RealTRMatrix RealTRMatrix]
  [source destination]
  (do
    (copy! source destination)
    destination))

(defmethod transfer! [clojure.lang.Sequential RealTRMatrix]
  [source ^RealTRMatrix destination]
  (let [ofst (.ofst destination)
        ld (.ld destination)
        n (.n destination)
        stripe-nav ^StripeNavigator (.stripe-nav destination)
        da (data-accessor destination)
        buf (.buf destination)]
    (loop [j 0 src source]
      (if (and src (< j n))
        (recur (inc j)
               (let [end (.end stripe-nav n j)]
                 (loop [i (.start stripe-nav n j) src src]
                   (if (and src (< i end))
                     (do (.set ^RealBufferAccessor da buf (+ ofst (* ld j) i) (first src))
                         (recur (inc i) (next src)))
                     src))))
        destination))))
