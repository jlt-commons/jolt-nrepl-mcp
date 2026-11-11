(ns jolt-nrepl-mcp.spec.bencode-spec
  "bencode, the nREPL wire format. Wire text is latin1 (one char per byte),
  so a string's length prefix counts UTF-8 bytes; decode reads one value at
  an index and answers nil until the text holds all of it."
  (:require [writ.spec :refer [spec ann graph law]]))

(spec jolt-nrepl-mcp.bencode {:require :tested})

(ann ->wire [String -> String])
(ann wire-> [String -> String])
(ann encode [Any -> String])
(ann decode [String Nat -> (Opt (Tuple Any Nat))])

(graph wire
  {:start :value
   :states {:value Any
            :text String
            :decoded (Opt (Tuple Any Nat))}
   :edges {:value {[encode] #{:text}}
           :text {[decode Nat] #{:decoded}
                  [->wire] #{:text}
                  [wire->] #{:text}}}
   :final [:decoded]})

(law wire-roundtrip
  (forall [s String] (= s (wire-> (->wire s)))))

(law wire-counts-bytes
  (= 5 (count (->wire "é→"))))

(law string-roundtrip
  (forall [s String]
    (let [e (encode s)]
      (= [s (count e)] (decode e 0)))))

(law int-roundtrip
  (forall [n Int]
    (let [e (encode n)]
      (= [n (count e)] (decode e 0)))))

(law list-roundtrip
  (forall [xs (List String)]
    (let [e (encode (vec xs))]
      (= [(vec xs) (count e)] (decode e 0)))))

(law map-roundtrip
  (forall [m (Map String Int)]
    (let [e (encode m)]
      (= [m (count e)] (decode e 0)))))

(law keyword-and-nil-encode-as-strings
  (and (= "2:op" (encode :op))
       (= "0:" (encode nil))))

(law map-keys-are-sorted
  (= "d1:ai1e1:bi2ee" (encode {"b" 2 "a" 1})))

(law decode-reads-at-an-index
  (= ["x" 6] (decode "i1e1:x" 3)))

(law incomplete-text-is-nil
  (forall [xs (List String)]
    (let [e (encode (conj (vec xs) "end"))]
      (nil? (decode (subs e 0 (dec (count e))) 0)))))

(law past-the-end-is-nil
  (forall [s String] (nil? (decode s (count s)))))

(law multi-byte-roundtrip
  (= "λ→😀" (wire-> (->wire "λ→😀"))))

(law astral-is-four-bytes
  (= "4:" (subs (encode "😀") 0 2)))

(law malformed-utf8-decodes-to-replacement
  (and (= "\uFFFD" (wire-> "\u00ff"))              ; not a lead byte
       (= "\uFFFDx" (wire-> "\u00c3x"))            ; lead, then no continuation
       (= "\uFFFD" (wire-> "\u00e2\u0086"))))      ; cut short at the end

(law other-values-encode-as-their-text
  (and (= "3:abc" (encode 'abc))
       (= "3:1.5" (encode 1.5))))

(law malformed-wire-text-is-nil
  (and (nil? (decode "i12" 0))     ; an integer with no end
       (nil? (decode "e" 0))       ; a close with nothing open
       (nil? (decode "abc" 0))     ; no length
       (nil? (decode "x:abc" 0)))) ; a length that is not a number
