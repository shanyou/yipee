(ns k8scvt.flat-validator
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [engine.core :refer :all])
  (:import [java.util.regex Pattern]))


;; patterns to determine if a string is a valid uuid
(def hexre "[\\da-f]")
(def uuid-regex (re-pattern (str hexre "{8}-"
                                 hexre "{4}-"
                                 hexre "{4}-"
                                 hexre "{4}-"
                                 hexre "{12}")))

(defn uuid? [x] (and (string? x) (re-matches uuid-regex x)))

;; top level wme being type checked; used when checking nested types
;; that vary depending on the type of the top level object
(def ^:dynamic *wme-to-check* nil)

;; wmes are records, so we turn them into maps when we want to print them
(defn to-map [wme] (dissoc (into {} wme) :__id))

;; Construct the different kinds of errors that can be generated by the validator
(defn generate-required-type-error [wme field tipe]
  (if (and (contains? wme field) (not= (field wme) ""))
    (insert! {:type :validation-error
              :validation-type :invalid-type
              :field field
              :expected tipe
              :wme (to-map wme)
              :value (field wme)})
    (insert! {:type :validation-error
              :validation-type :missing-required-field
              :missing-field field
              :wme (to-map wme)})))

(defn generate-type-error [wme field tipe]
  (when (and (contains? wme field) (not= (field wme) ""))
    (insert! {:type :validation-error
              :validation-type :invalid-type
              :field field
              :expected tipe
              :wme (to-map wme)
              :value (field wme)})))

(defn generate-reference-error [wme field tipe]
  (insert! {:type :validation-error
            :validation-type :invalid-reference
            :field field
            :reference-type tipe
            :wme (to-map wme)
            :value (field wme)}))

;; Tests for primitive types
(defn- positive? [x] (> x 0))
(defn- non-negative? [x] (>= x 0))
(defn- digit-string? [x] (re-matches #"^[\d]+$" x))
(defn- array? [x] (or (seq? x) (vector? x)))

(def predicates
  {:string                      string?
   :string-array                #(and (array? %) (every? string? %))
   :empty-string-array          #(and (array? %) (empty? %))
   ;; some labels are turned into strings because their syntax includes '/' which
   ;; looks like a clojure namespace, so we need to allow either one
   :keyword-or-str              #(or (keyword? %) (string? %))
   :integer                     integer?
   :non-negative-integer        #(and (integer? %) (non-negative? %))
   :non-negative-integer-string #(and (string? %)
                                      (digit-string? %)
                                      (non-negative? (read-string %)))
   :positive-integer            #(and (integer? %) (positive? %))
   :positive-integer-string     #(and (string? %)
                                      (digit-string? %)
                                      (positive? (read-string %)))
   :boolean                     #(or (= % true) (= % false))
   :json                        #(try (do (with-out-str (json/write-str %)) true)
                                      (catch Exception _ false))
   :uuid                        uuid?
   :uuid-ref                    uuid?
   :uuid-ref-array              #(and (array? %) (every? uuid? %))})

;; We now support chunks of json for certain fields that are pure Kubernetes
;; and have no corresponding swarm versions. We describe these with:
;; fixed-map -> [:fixed-map
;;               [<defined key> <value type>]
;;               [<defined key> <value type>]]
;; open-map -> [:open-map
;;              [<defined key> <value type>]
;;              [<defined key> <value type>]
;;              ... potentially other ignored key/value pairs]
;; key-value (such as labels) -> [:key-value <key type> <value type>]
;; array - [:array <entry type>]
;; json simple type -> predicate from above
;; one of a set of specific values
;;  (e.g. the keyword :annotations or the string "foo") -> #{:annotations "foo"}
;; optional value -> [:? <entry>]
;; multiple alternatives -> [:or <type> <type> ...]
;; alternatives selected based on another field of the wme ->
;;  [:case <field name of discriminator>
;;   [<disc value> <type>] ... <optional type if no <disc> matches>]
;;
;; e.g. metadata = [:map [:? [:annotations [:key-value :keyword :string]]]
;;                       [:? [:labels [:key-value :keyword-or-str :string]]]
;;                       [:name :string]
;;                       [:? [:namespace :string]]]

;; Used recursively in specialized checks below
(declare type-check)

;; All structured types and types with behavior (like :case and :or) are
;; represented as vectors with the type selector as the first element.
(defn compound-type? [ctype type-val]
  (and (vector? type-val) (= ctype (first type-val))))

(defn case-type? [typ]      (compound-type? :case typ))
(defn or-type? [typ]        (compound-type? :or typ))
(defn fixed-map-type? [typ] (compound-type? :fixed-map typ))
(defn open-map-type? [typ]  (compound-type? :open-map typ))
(defn key-value-type? [typ] (compound-type? :key-value typ))
(defn array-type? [typ]     (compound-type? :array typ))
(defn optional-type? [typ]  (compound-type? :? typ))

;; check a vector containing an arbitrary number of pairs with a single
;; key type and a single value type
(defn json-key-value-type [[_ key-type val-type] item]
  (and (map? item)
       (every? (fn [[k v]]
                 (and (type-check key-type k)
                      (type-check val-type v)))
               item)))

;; check a map with a set of specified keys and any number of additional keys
(defn json-open-map-type [typ item]
  (and (map? item)
       (every? (fn [field-type]
                 (let [[base-type is-optional?] (if (optional-type? field-type)
                                                  [(second field-type) true]
                                                  [field-type false])
                       field-val ((first base-type) item)]
                   (if (nil? field-val)
                     is-optional?
                     (type-check (second base-type) field-val))))
               (rest typ))))

;; check a map with a set of specified keys and fail if any additional keys are found
(defn json-fixed-map-type [typ item]
  (and (json-open-map-type typ item)
       (not (some (fn [key]
                    (not (some (fn [field-type]
                                 (let [base-type (if (optional-type? field-type)
                                                   (second field-type)
                                                   field-type)
                                       key-type (first base-type)]
                                   (= key-type key)))
                               (rest typ))))
                  (keys (dissoc item :type :id))))))

;; check a homogeneous vector
(defn json-array-type [typ item]
  (let [atype (second typ)]
    (and (or (seq? item) (vector? item)) (every? #(type-check atype %) item))))

;; check an item but don't fail if it is missing entirely
(defn json-optional-type [typ item]
  (or (nil? item) (type-check typ item)))

;; check a 'case' type in which a discriminating field in the wme selects
;; among a set of distinct types
(defn case-type-check [case-type item]
  (let [[_ func & body] case-type
        key (func *wme-to-check*)]
    (loop [current body]
      (let [[curval & tail] current
            [k v] (if (list? curval) (first curval) curval)]
        (cond (nil? k) false
              (nil? v) (type-check k item)
              (= (name k) (name key)) (type-check v item)
              :else (recur tail))))))

;; main recursive type checking function
(defn type-check [typ item]
  (cond (case-type? typ)       (case-type-check typ item)
        (or-type? typ)         (some #(type-check % item) typ)
        (fixed-map-type? typ)  (json-fixed-map-type typ item)
        (open-map-type? typ)   (json-open-map-type typ item)
        (key-value-type? typ)  (json-key-value-type typ item)
        (array-type? typ)      (json-array-type typ item)
        (optional-type? typ)   (json-optional-type (first typ) item)
        (set? typ)             (typ item)
        (= (type typ) Pattern) (and (string? item) (re-matches typ item))
        :else                  ((or (predicates typ) (fn [& _] false)) item)))

;; top level type checker; binds *wme-to-check* to the wme so discriminating
;; fields referenced by lower level 'case' tests can be accessed
(defn check-type [type-val field wme is-optional?]
  (binding [*wme-to-check* wme]
    (let [value (field wme)]
      (or (= value "")
          (and is-optional? (= value nil))
          (type-check type-val value)))))

;; generate a string representation of a type for inclusion in documentation
(defn translate-type [typ]
  (let [choice #(str "(" (str/join " | " (mapv translate-type %)) ")")
        strquote #(str "\"" % "\"")]
    (cond (vector? typ)
          (case (first typ)
            :case
            (str "case "
                 (name (second typ))
                 ": "
                 (str/join "; "
                           (mapv (fn [[k v]]
                                   (if (nil? v)
                                     (str "otherwise=>(" (translate-type k) ")")
                                     (str "when \"" k
                                          "\"=>(" (translate-type v) ")")))
                                 (nthrest typ 2))))

            :or
            (choice (rest typ))

            (:fixed-map :open-map)
            (str
             "{"
             (str/join ", "
                       (mapv (fn [item]
                               (let [is-optional? (optional-type? item)
                                     real-item (if is-optional? (second item) item)
                                     [ktype vtype] real-item
                                     base-str (str (str "\"" (name ktype) "\"")
                                                   "=>"
                                                   (translate-type vtype))]
                                 (if is-optional? (str "(" base-str ")?") base-str)))
                             (rest typ)))
             (when (= (first typ) :open-map) "...")
             "}")

            :key-value
            (let [[ktype vtype] (rest typ)]
              (str "{"
                   ;; key/values turn into keyword/string on json import but
                   ;; should go back to string/string on display
                   (if (keyword? ktype)
                     (strquote (name ktype))
                       (translate-type ktype))
                   "=>"
                   (translate-type vtype)
                   ", ...}"))

            :array
            (str "[" (translate-type (second typ)) "]")

            :?
            (str "(" (translate-type (second typ)) ")?"))

          (set? typ)
          (choice typ)

          ;; primitive types
          (keyword? typ)
          (name typ)

          ;; singleton value types
          (string? typ)
          (strquote typ)

          ;; regex types
          (= (type typ) Pattern)
          (str "#\"" typ "\"")

          :else
          typ)))

;; create string versions of generated errors
(defn format-flat-validation-error [verr]
  (case (:validation-type verr)
    :invalid-type
    (format "Object (%s): invalid type -- field: '%s', expected: '%s'"
            (json/write-str (:wme verr))
            (name (:field verr))
            (translate-type (:expected verr)))

    :missing-required-field
    (format "Object (%s): missing required field: '%s'"
            (json/write-str (:wme verr))
            (name (:missing-field verr)))

    :invalid-reference
    (format (str "Object (%s): invalid reference - field: '%s' refers "
                 "to non-existent object of type: '%s'")
            (json/write-str (:wme verr))
            (name (:field verr))
            (translate-type (:reference-type verr)))))

;; Main header for documentation file
(def header "# Flat Format\n## Flat Format \"Objects\"\nThe term \"Flat Format\" refers to the lack of (most) hierarchical\nstructure in the model below. Any relationships between objects are\nmanaged via *id* references. A flat format document consists of a map\nfrom object type names to arrays of corresponding objects. In addition\nto the specific fields mentioned below, each object contains a *type*\nfield whose value is the string name of the object type and an *id*\nfield containing a uuid string uniquely representing the object. We\nintend that some objects will contain attributes specific to\nparticular orchestrators. Because of this, it's critical that users\noverwrite fields within an existing object rather than construct\ninstances from scratch so any \"extra\" information used internally is\nnot lost.")

;; atom collecting all documentation from 'defflat' forms
(def doc-text (atom ""))

;; write collected documentation to a file
(defn dump-documentation [file-name]
  (spit file-name (str header "\n\n" @doc-text)))

;; include main header in generated docs
(defn doc-header [header]
  (swap! doc-text str "\n### " header "\n"))

;; Create the textual description for a top level flat object -- different
;; from the kubernetes chunk types described above.
(defn type-description [items]
  (let [item (first items)
        emph #(str "*" % "*")]
    (cond (nil? item) "\n"
          (or (keyword? item)
              (and (vector? item)
                   (keyword? (first item)))) (type-description (rest items))
          :else (cond (string? item) (format "(%s)\n" item)
                      (map? item) (if (contains? item :options)
                                    (str "("
                                         (when-let [pre (:pre-text item)]
                                           (str pre ". "))
                                         (str/join ", " (map emph (:options item)))
                                         (when-let [default (:default item)]
                                           (str " -- default: " (emph default)))
                                         (when-let [post (:post-text item)]
                                           (str ". " post))
                                         ")\n")
                                    "\n")
                      :else (throw (RuntimeException.
                                    (str "Unrecognized type description: "
                                         item)))))))

;; generate documentation for a single 'defflat' type
(defn generate-docs [type-name doc-string fields]
  (swap! doc-text str "#### " type-name "\n" doc-string "\n"
         (with-out-str
           (doseq [field fields]
             (printf "- *%s* **%s** %s"
                     (name (first field))
                     (translate-type (second field))
                     (type-description (nthrest field 2)))))))


;; generate rules that can type check a field of a 'defflat'
;; type. Besides checking types, also checks if target wmes exist for
;; all non-optional references.
(defn gen-field-rules [type-name field]
  (let [field-keyword (first field)
        [base-field-type type-modifier & _] (rest field)
        field-type (if (and (= base-field-type :string)
                            (map? type-modifier)
                            (contains? type-modifier :options))
                     (set (:options type-modifier))
                     base-field-type)
        is-optional? (= (last field) :optional)]
    `[(defrule ~(gensym
                 (str "validate-" (name type-name) "-" (name field-keyword)))
        ~@(let [var# (gensym (str "?" (name type-name)))]
            `[[~var# ~type-name
               (not (check-type ~field-type ~field-keyword ~var# ~is-optional?))]
              ~(symbol "=>")
              ~(if is-optional?
                 `(generate-type-error ~var# ~field-keyword '~field-type)
                 `(generate-required-type-error
                   ~var# ~field-keyword ~field-type))]))
      ~@(when (and (= field-type :uuid-ref)
                   (not is-optional?)
                   (not= (last field) :allow-missing-target))
          [`(defrule ~(gensym
                       (str "validate-" (name type-name) "-"
                            (name field-keyword) "-reference"))
              ~@(let [var# (gensym (str "?" (name field-keyword)))
                      refvar# (gensym (str "?" (name (nth field 2))))]
                  `[[~var# ~type-name]
                    [:not [~refvar# ~(nth field 2)
                           (= (:id ~refvar#) (~field-keyword ~var#))]]
                    ~(symbol "=>")
                    (generate-reference-error ~var# ~field-keyword
                                              ~(nth field 2))]))])]))

;; generate rules for all the fields of a 'defflat' type
(defn gen-rules [type-name fields]
  (mapcat (partial gen-field-rules type-name) fields))

;; macro used to define a flat object type
(defmacro defflat [type-name doc-string & fields]
  (generate-docs type-name doc-string fields)
  `(do ~@(gen-rules (keyword type-name) fields)))

;; From here on is the actual flat format definition.

;; This section describes flat objects arising from either Kubernetes *or* Swarm
(doc-header "Orchestrator Agnostic")

(defflat annotation
  "Additional information about another object (including overrides)"
  [:key :string "name of annotation"]
  [:value :json "value of annotation" :optional]
  [:annotated :uuid-ref :annotatable "object being annotated"])

(defflat app-info
  "High-level data about an entire model"
  [:description :string :optional]
  [:logo :string :optional]
  [:name :string :optional]
  [:readme :string :optional])

(defflat command
  "Docker command for running a container"
  [:value :string-array]
  [:container :uuid-ref :container "reference to container"
   :allow-missing-target])

(defflat config
  "Config map (behaves mostly like an unencrypted secret)"
  [:default-mode :non-negative-integer-string
   "mode to apply to each config item if not specified"
   :optional]
  [:name :string "name of config volume"]
  [:map-name :string "name of config map"])

(defflat config-ref
  "Reference to a config map/volume"
  [:container :uuid-ref :container "reference to container"]
  [:container-name :string "needed for lookup after storage"]
  [:name :string "name of config volume"]
  [:path :string "mount path for volume in container"]
  [:config :uuid-ref :config "id of config volume"])

(defflat container
  "Docker container being managed"
  [:name :string]
  [:cgroup :uuid-ref :container-group "reference to container group"])

(defflat container-group
  "Multi-container unit (support for Kubernetes pods)"
  [:name :string]
  [:pod :uuid-ref :podval "reference to pod structure representing group"
   :allow-missing-target]
  [:source :string {:options ["auto" "k8s"]}]
  [:controller-type :string {:options ["Deployment" "DaemonSet" "StatefulSet"
                                       "Job" "CronJob"]}]
  [:containers :uuid-ref-array]
  [:container-names :string-array "needed for storing in yipee"])

(defflat dependency
  "Startup ordering relationship"
  [:depender :uuid-ref :container "reference to dependent container"]
  [:dependee :uuid-ref :container "reference to independent container"])

(defflat deployment-spec
  "Defines how many instances of a container group should be deployed and in what \"mode\" (*replicated* or *allnodes*)"
  [:count :non-negative-integer]
  [:mode :string {:options ["replicated", "allnodes"]}]
  [:cgroup :uuid-ref :container-group "reference to container group"]
  [:service-name :string "name of associated headless service"]
  [:controller-type :string {:options ["Deployment"
                                       "DaemonSet"
                                       "StatefulSet"
                                       "CronJob"]}]
  [:termination-grace-period :non-negative-integer
   "how long to wait before killing pods"]
  [:update-strategy [:case :controller-type
                     ["StatefulSet" [:fixed-map
                                     [:type #{"RollingUpdate"}]
                                     [:?
                                      [:rollingUpdate
                                       [:fixed-map
                                        [:partition :non-negative-integer]]]]]]
                     ["Deployment" [:or
                                    [:fixed-map [:type #{"Recreate"}]]
                                    [:fixed-map
                                     [:type #{"RollingUpdate"}]
                                     [:?
                                      [:rollingUpdate [:fixed-map
                                                       [:?
                                                        [:maxSurge
                                                         [:or
                                                          :non-negative-integer
                                                          :non-negative-integer-string
                                                          #"[1-9][0-9]?[%]"]]]
                                                       [:?
                                                        [:maxUnavailable
                                                         [:or
                                                          :non-negative-integer
                                                          :non-negative-integer-string
                                                          #"[1-9][0-9]?[%]"]]]]]]]]]
                     ["DaemonSet" [:or
                                   [:fixed-map [:type #{"OnDelete"}]]
                                   [:fixed-map
                                    [:type #{"RollingUpdate"}]
                                    [:?
                                     [:rollingUpdate
                                      [:fixed-map
                                       [:maxUnavailable
                                        [:or
                                         :positive-integer
                                         :positive-integer-string
                                         #"[1-9][0-9]?[%]"]]]]]]]]]
   :optional]
  [:pod-management-policy :string {:options ["OrderedReady" "Parallel"]}])

(defflat development-config
  "Yipee development override"
  [:image :string]
  [:repository :string]
  [:tag :string]
  [:configured :uuid-ref :annotatable "reference to configured object"])

(defflat empty-dir-volume
  "Empty directory on pod host for scratch use"
  [:name :string]
  [:annotations :json "will go away - currently used to support ui format"
   :optional]
  [:medium :string {:options ["Memory" "<empty string>"] :default "<empty string>"
                    :post-text "whether or not to mount the directory as tmpfs"}]
  [:cgroup :uuid-ref :container-group "needed to disambiguate empty dir volumes -- they don't have unique instances like PV claims"])

(defflat entrypoint
  "Docker entrypoint for running a container"
  [:value :string-array]
  [:container :uuid-ref :container])

(defflat environment-var
  "Enviroment variable"
  [:key :string]
  [:value :string :optional]
  [:valueFrom [:or
               [:fixed-map
                [:configMapKeyRef [:fixed-map
                                   [:key :string]
                                   [:name :string]
                                   [:? [:optional :boolean]]]]]
               [:fixed-map
                [:fieldRef [:fixed-map
                            [:? [:apiVersion :string]]
                            [:fieldPath :string]]]]
               [:fixed-map
                [:resourceFieldRef [:fixed-map
                                    [:? [:containerName :string]]
                                    [:? [:divisor :string]]
                                    [:resource :string]]]]
               [:fixed-map
                [:secretKeyRef [:fixed-map
                                [:key :string]
                                [:name :string]
                                [:? [:optional :boolean]]]]]]
   :optional]
  [:container :uuid-ref :container "reference to container"])

(defflat external-config
  "Yipee external override"
  [:image :string]
  [:server :string]
  [:proxy-type :string {:options ["tcp" "udp"]}]
  [:ports [:array #"(([^:\\s]+:)?[\\d]+:)?[\\d]+([/](udp|tcp))?"]]
  [:configured :uuid-ref :annotatable "reference to configured object"])

(defflat extra-hosts
  "Hostname/IP mappings for additional hosts"
  [:value :string-array]
  [:cgroup :uuid-ref :container-group
   "reference to container group w/ mappings"])

(defflat healthcheck
  "Specification for a check operation to perform on a container"
  [:healthcmd :string-array :optional]
  [:interval :non-negative-integer :optional]
  [:retries :non-negative-integer :optional]
  [:timeout :non-negative-integer :optional]
  [:check-type :string {:options ["liveness" "readiness" "both"]}]
  [:container :uuid-ref :container "reference to container"])

(defflat image
  "Image run by a container"
  [:value :string]
  [:container :uuid-ref :container "reference to container running image"])

(defflat label
  "Tag placed on searchable unit"
  [:key :string]
  [:value :string]
  [:cgroup :uuid-ref :container-group "reference to labeled container group"])

(defflat port-mapping
  "Mapping between container port and external port"
  [:name :string]
  [:internal :string] ;; can be named port
  [:external :non-negative-integer-string]
  [:protocol :string {:options ["tcp" "udp"]} :optional]
  [:container :uuid-ref :container "reference to container" :optional]
  [:defining-service :uuid-ref :k8s-service
   "explicit service that called out port; empty string if generated from compatibility mode" :optional])

(defflat restart
  "Conditions under which a container group should be restarted"
  [:value :string {:options ["always" "none" "unless-stopped"]}]
  [:cgroup :uuid-ref :container-group
   "reference to restarting container group"])

(defflat secret
"Definition of secret value (needs work as the set of fields is not currently fixed - *external*, *file*, *alternate-name* vary depending on the secret"
  [:name :string]
  [:source :string "empty string if \"external\", file name if \"file\""]
  [:alternate-name :string
   "empty string if \"file\" or \"external\" without name"]
  [:default-mode :non-negative-integer-string "mode to apply to each secret item if not specified"])

(defflat secret-ref
  "Reference to existing secret from a container"
  [:uid :non-negative-integer-string]
  [:gid :non-negative-integer-string]
  [:mode :non-negative-integer-string]
  [:secret-volume :uuid-ref :secret-volume "reference to secret volume"]
  [:secret :uuid-ref :secret "reference to secret" :allow-missing-target]
  [:source :string]
  [:target :string]
  [:container :uuid-ref :container "reference to container using secret"])

(defflat volume
  "Storage specification"
  [:name :string]
  [:annotations :json "will go away - currently used to support ui format"]
  [:is-template :boolean "whether or not this volume object represents a StatefulSet VolumeClaimTemplate rather than a direct volume claim"]
  [:volume-mode :string {:options ["Filesystem" "Block"]
                         :default "Filesystem"}
   :optional]
  [:access-modes [:array #{"ReadOnlyMany" "ReadWriteOnce" "ReadWriteMany"}]
   "one or more of: *ReadOnlyMany*, *ReadWriteOnce*, *ReadWriteMany*" :optional]
  [:storage-class :string "name of predefined cluster storage class" :optional]
  [:storage :string "amount of storage for a PersistentVolumeClaim -- allows units: E, P, T, G, M, K - powers of 10: Exa, Peta, Tera, Giga, Mega, Kilo and Ei, Pi, Ti, Gi, Mi, Ki - powers of two (i.e. Gi is 1024*1024*1024 while G is 1000*1000*1000"
   :optional]
  [:selector [:fixed-map
              [:? [:matchExpressions
                   [:or
                    [:fixed-map
                     [:key :string]
                     [:operator #{"In" "NotIn"}]
                     [:values :string-array]]
                    [:fixed-map
                     [:key :string]
                     [:operator #{"Exists" "DoesNotExist"}]
                     [:values :empty-string-array]]]]]
              [:? [:matchLabels [:key-value :keyword-or-str :string]]]]
   "used for PersistentVolumeClaims -- staying compatible with k8s-service for now... both matchLabels and matchExpressions for attributes of persistent volumes (see: [persistent volume docs](https://kubernetes.io/docs/concepts/storage/persistent-volumes/#persistent-volumes))" :optional])

(defflat volume-ref
"Reference from container to volume"
  [:path :string]
  [:volume-name :string]
  [:volume :uuid-ref :referable-volume "reference to volume"]
  [:access-mode :string {:options ["ReadOnlyMany" "ReadWriteOnce"
                                   "ReadWriteMany"]}]
  [:container-name :string "name of container using volume"]
  [:container :uuid-ref :container "reference to container using volume"])

(doc-header "Kubernetes Only")

(defflat top-label
  "Kubernetes supports labels at many levels. We mostly care about labels in selectors but you can also place labels at the top levels of constructs like *Deployments*. These are those auxiliary labels."
  [:key :string]
  [:value :string]
  [:cgroup :uuid-ref :container-group "reference to labeled container group"])

(doc-header "Compose Only")

(defflat image-pull-policy
  "When to pull a new image"
  [:value :string {:options ["Always" "IfNotPresent"]}]
  [:container :uuid-ref :container "reference to container using image"])

(defflat k8s-namespace
  "Kubernetes supports explicit namespaces"
  [:name :string]
  [:label-name :string])

(defflat k8s-service
  "Stores the selector and metadata derived from a top level Kubernetes service"
  [:name :string]
  [:metadata [:open-map
              [:? [:annotations [:key-value :keyword-or-str :string]]]
              [:? [:labels [:key-value :keyword-or-str :string]]]
              [:? [:selector [:key-value :keyword-or-str :string]]]
              [:name :string]
              [:? [:namespace :string]]]]
  [:selector [:key-value :keyword-or-str :string] :optional]
  [:service-type :string {:options ["ClusterIP" "NodePort" "LoadBalancer"
                                    "ExternalName"]}]
  [:cluster-ip :string "if present, static IP for service or \"None\""
   :optional]
  [:node-port :string "if present, staticly defined port for service"
   :optional])

;; Generate documentation into the target directory
(dump-documentation "src/flat-format.md")
