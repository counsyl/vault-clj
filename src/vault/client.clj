(ns vault.client
  "Protocol for interacting with Vault to fetch secrets using the HTTP API. This
  client is focused on the app-id authentication scheme."
  (:require
    [clj-http.client :as http]
    [clojure.string :as str]
    [clojure.tools.logging :as log]))


;; ## Client Protocol

(defprotocol Client
  "Protocol for fetching secrets from Vault."

  (authenticate!
    [client auth-type credentials]
    "Updates the client's internal state by authenticating with the given
    credentials. Possible arguments:

    - :token \"...\"
    - :app-id {:app \"lambda_ci\", :user \"...\"}")

  (list-secrets
    [client path]
    "List the secrets located under a path.")

  (read-secret
    [client path]
    "Reads a secret from a specific path."))



;; ## HTTP API Client

(defn- check-path!
  "Validates that the given path."
  [path]
  (when-not (string? path)
    (throw (IllegalArgumentException.
             (str "Secret path must be a string, got: " (pr-str path))))))


(defn- check-auth!
  "Validates that the client is authenticated."
  [token-ref]
  (when-not @token-ref
    (throw (IllegalStateException.
             "Cannot read path with unauthenticated client."))))


(defn- authenticate-token!
  [token-ref token]
  (when-not (string? token)
    (throw (IllegalArgumentException. "Token credential must be a string")))
  ; TODO: test auth?
  (reset! token-ref token))


(defn- authenticate-app!
  [api-url token-ref credentials]
  (let [{:keys [app user]} credentials
        response (http/post (str api-url "/v1/auth/app-id/login")
                   {:form-params {:app_id app, :user_id user}
                    :content-type :json
                    :accept :json
                    :as :json})]
    (when-let [client-token (get-in response [:body :auth :client_token])]
      (log/infof "Successfully authenticated to Vault app-id %s for policies: %s"
                 app (str/join ", " (get-in response [:body :auth :policies])))
      (reset! token-ref client-token))))


(defrecord HTTPClient
  [api-url token]

  Client

  (authenticate!
    [this auth-type credentials]
    (case auth-type
      :token (authenticate-token! token credentials)
      :app-id (authenticate-app! api-url token credentials)
      ; TODO: support LDAP auth

      ; Unknown type
      (throw (ex-info (str "Unsupported auth-type " (pr-str auth-type))
                      {:auth-type auth-type})))
    this)


  (list-secrets
    [this path]
    (check-path! path)
    (check-auth! token)
    (let [response (http/get (str api-url "/v1/" path)
                     {:query-params {:list true}
                      :headers {"X-Vault-Token" @token}
                      :accept :json
                      :as :json})
          data (get-in response [:body :data :keys])]
      (log/infof "List %s (%d results)" path (count data))
      data))


  (read-secret
    [this path]
    (check-path! path)
    (check-auth! token)
    (let [response (http/get (str api-url "/v1/" path)
                     {:headers {"X-Vault-Token" @token}
                      :accept :json
                      :as :json})]
      (log/infof "Read %s (valid for %d seconds)"
                 path (get-in response [:body :lease_duration]))
      (get-in response [:body :data]))))


;; Remove automatic constructors.
(ns-unmap *ns* '->HTTPClient)
(ns-unmap *ns* 'map->HTTPClient)


(defn http-client
  "Constructs a new HTTP Vault client."
  [api-url]
  (when-not (string? api-url)
    (throw (IllegalArgumentException.
             (str "Vault api-url must be a string, got: " (pr-str api-url)))))
  (HTTPClient. api-url (atom nil)))
