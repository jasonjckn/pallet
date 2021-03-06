(ns pallet.compute.vmfest
  "A vmfest provider.

   An example service configuration in ~/.pallet/config.clj

       :vb {:provider \"virtualbox\"
            :images {:centos-5-3 {:description \"CentOS 5.3 32bit\"
                                  :uuid \"4697bdf7-7acf-4a20-8c28-e20b6bb58e25\"
                                  :os-family :centos
                                  :os-version \"5.3\"
                                  :os-type-id \"RedHat\"}
                     :ubuntu-10-04 {:description \"Ubuntu 10.04 32bit\"
                                    :uuid \"8a31e3aa-0d46-41a5-936d-25130dcb16b7\"
                                    :os-family :ubuntu
                                    :os-version \"10.04\"
                                    :os-type-id \"Ubuntu\"
                                    :username
                                    :password}}
            :model-path \"/Volumes/My Book/vms/disks\"
            :node-path \"/Volumes/My Book/vms/nodes\"}

   The uuid's can be found using vboxmanage
       vboxmanage list hdds

   The images are disks that are immutable.  The virtualbox extensions need
   to be installed on the image."
  (:require
   [vmfest.virtualbox.virtualbox :as virtualbox]
   [vmfest.virtualbox.machine :as machine]
   [vmfest.virtualbox.model :as model]
   [vmfest.manager :as manager]
   [pallet.compute :as compute]
   [pallet.compute.jvm :as jvm]
   [pallet.compute.implementation :as implementation]
   [pallet.core :as core]
   [pallet.script :as script]
   [pallet.execute :as execute]
   [pallet.resource :as resource]
   [clojure.contrib.condition :as condition]
   [clojure.string :as string]
   [clojure.contrib.logging :as logging]))

(defn supported-providers []
  ["virtualbox"])

(def os-family-name
  {:ubuntu "Ubuntu"
   :centos "RedHat"
   ;:rhel "RedHat"
   :rhel "RedHat_64"})

(def os-family-from-name
  (zipmap (vals os-family-name) (keys os-family-name)))

(extend-type vmfest.virtualbox.model.Machine
  pallet.compute/Node
  (ssh-port [node] 22)
  (primary-ip
   [node]
   (condition/handler-case
    :type
    (manager/get-ip node)
    (handle :vbox-runtime
      (manager/get-extra-data node "/pallet/ip"))))
  (private-ip [node] nil)
  (is-64bit?
   [node]
   (let [os-type-id (:os-type-id (manager/as-map node))]
     (re-find #"64 bit" os-type-id)))
  (tag
   [node]
   (manager/get-extra-data node "/pallet/tag"))
  (hostname
   [node]
   (:name (manager/as-map node)))
  (os-family
   [node]
   (let [os-name (:os-type-id (manager/as-map node))]
     (or
      (keyword (manager/get-extra-data node "/pallet/os-family"))
      (os-family-from-name os-name os-name)
      :centos) ;; hack!
     ))
  (os-version
   [node]
   (or
      (manager/get-extra-data node "/pallet/os-version")
      "5.3"))
  (running? [node] true)
  (terminated? [node] false)
  (id [node] (:id node)))

(defn nil-if-blank [x]
  (if (string/blank? x) nil x))

(defn wait-for-ip
  "Wait for the machines IP to become available."
  [machine]
  (loop []
    (try
      (let [ip (try (manager/get-ip machine)
                    (catch org.virtualbox_3_2.RuntimeFaultMsg e
                      (logging/warn
                       (format "wait-for-ip: Machine %s not started yet..." machine)))
                    (catch clojure.contrib.condition.Condition e
                      (logging/warn
                       (format "wait-for-ip: Machine %s is not accessible yet..." machine))))]
        (when (string/blank? ip)
          (Thread/sleep 2000)
          (recur))))))


(defn machine-name
  "Generate a machine name"
  [tag n]
  (format "%s-%s" tag n))

(defprotocol VirtualBoxService
  (os-families [compute] "Return supported os-families")
  (medium-formats [compute] "Return supported medium-formats"))

(defn node-data [m]
  (let [node-map (manager/as-map m)
        attributes (select-keys node-map [:name :description :session-state])
        open? (= :open (:session-state node-map))
        ip (when open? (manager/get-ip m))
        tag (when open? (manager/get-extra-data m "/pallet/tag"))]
    (into attributes {:ip ip :tag tag}) ))

(defn node-infos [compute-service]
  (let [nodes (manager/machines compute-service)]
    (map node-data nodes)))


(defn create-node
  [compute node-path node-type machine-name images image-id tag-name
   init-script user]
  {:pre [image-id]}
  (logging/trace (format "Creating node from image-id: %s" image-id))
  (let [machine (manager/instance
                 compute machine-name image-id :micro node-path)
        image (image-id images)]
    (manager/set-extra-data machine "/pallet/tag" tag-name)
    (manager/set-extra-data machine "/pallet/os-family" (name (:os-family image)))
    (manager/set-extra-data machine "/pallet/os-version" (:os-version image))
    ;; (manager/add-startup-command machine 1 init-script )
    (manager/start machine :session-type "vrdp")
    (logging/trace "Wait to allow boot")
    (Thread/sleep 15000)                ; wait minimal time for vm to boot
    (logging/trace "Waiting for ip")
    (wait-for-ip machine)
    (Thread/sleep 4000)
    (logging/trace (format "Bootstrapping %s" (manager/get-ip machine)))
    (script/with-template
      (resource/script-template {:node-type {:image image}})
      (execute/remote-sudo
       (manager/get-ip machine)
       init-script
       (if (:username image)
         (pallet.utils/make-user
          (:username image)
          :password (:password image)
          :no-sudo (:no-sudo image))
         user)))))

(defn- equality-match
  [image-properties kw arg]
  (= (image-properties kw) arg))

(defn- regexp-match
  [image-properties kw arg]
  (re-matches (re-pattern arg) (image-properties kw)))

(def template-matchers
  {:os-version-matches (fn [image-properties kw arg]
                         (regexp-match image-properties :os-version arg))})

(defn image-from-template
  "Use the template to select an image from the image map."
  [images template]
  (if-let [image-id (:image-id template)]
    (image-id images)
    (->
     (filter
      (fn image-matches? [[image-name image-properties]]
        (every?
         #(((first %) template-matchers equality-match)
           image-properties (first %) (second %))
         (dissoc template :image-id :inbound-ports)))
      images)
     ffirst)))

(deftype VmfestService
    [server images locations]
  pallet.compute/ComputeService
  (nodes
   [compute-service]
   (manager/machines server))

  (ensure-os-family
   [compute-service request]
   request)

  ;; Not implemented
  ;; (build-node-template)

  (run-nodes
   [compute-service node-type node-count request init-script]
   (try
     (let [image-id (or (image-from-template images (-> node-type :image))
                        (throw (RuntimeException.
                                (format "No matching image for %s"
                                        (pr-str (-> node-type :image))))))
           tag-name (name (:tag node-type))
           current-machines-in-tag (filter
                                    #(= tag-name
                                        (manager/get-extra-data
                                         % "/pallet/tag"))
                                    (manager/machines server))
           current-machine-names (into #{}
                                       (map
                                        #(:name (manager/as-map %))
                                        current-machines-in-tag))
           target-indices (range (+ node-count
                                    (count current-machines-in-tag)))
           target-machine-names (into #{}
                                      (map
                                       #(machine-name tag-name %)
                                       target-indices))
           target-machines-already-existing (clojure.set/intersection
                                             current-machine-names
                                             target-machine-names)
           target-machines-to-create (clojure.set/difference
                                      target-machine-names
                                      target-machines-already-existing)]
       (logging/debug (str "current-machine-names " current-machine-names))
       (logging/debug (str "target-machine-names " target-machine-names))
       (logging/debug (str "target-machines-already-existing "
                           target-machines-already-existing))
       (logging/debug (str "target-machines-to-create"
                           target-machines-to-create))
       (binding [manager/*images* images]
         (doseq [name target-machines-to-create]
           (create-node
            server (:node-path locations) node-type name
            images image-id tag-name
            init-script
            (:user request)))))))

  (reboot
   [compute nodes]
   (compute/shutdown server nodes nil)
   (compute/boot-if-down server nodes))

  (boot-if-down
   [compute nodes]
   (doseq [node nodes]
     (manager/start node)))

  (shutdown-node
   [compute node _]
   ;; todo: wait for completion
   (logging/info (format "Shutting down %s" (pr-str node)))
   (manager/power-down node)
   (manager/wait-for-machine-state node [:powered-off] 10000))

  (shutdown
   [compute nodes user]
   (doseq [node nodes]
     (compute/shutdown-node server node user)))

  (destroy-nodes-with-tag
    [compute tag-name]
    (doseq [machine
            (filter
             #(= tag-name (manager/get-extra-data % "/pallet/tag"))
             (manager/machines server))]
      (compute/destroy-node compute machine)))

  (destroy-node
   [compute node]
   {:pre [node]}
   (compute/shutdown-node compute node nil)
   (manager/destroy node))

  (close [compute]))

;;;; Compute service
(defmethod implementation/service :virtualbox
  [_ {:keys [url identity credential images node-path model-path locations]
      :or {url "http://localhost:18083/"
           identity "test"
           credential "test"}
      :as options}]
  (let [locations (or locations
                      {:local (select-keys options [:node-path :model-path])})]
    (VmfestService.
     (vmfest.virtualbox.model.Server. url identity credential)
     images
     (val (first locations)))))

(defmethod clojure.core/print-method vmfest.virtualbox.model.Machine
  [node writer]
  (.write
   writer
   (format
    "%14s\t %14s\t public: %s"
    (compute/hostname node)
    (compute/tag node)
    (compute/primary-ip node))))
