# Test and deployment credentials

Supply credentials through the local environment or CI secrets. Local `.env` files,
deployment YAML, archives, and runtime screenshots are not committed.
Copy `conf/dwh-entry/*.example` or `conf/global-multi-user-test.yaml.example`
to their corresponding local configuration paths and replace placeholders.

Integration scripts use `DORIS_TEST_PASSWORD` for the Doris root account and its
gateway login. Other scenarios require `MULTI_USER_TEST_PASSWORD`,
`PLATFORM_TEST_PASSWORD`, `CLUSTER_B_ADMIN_PASSWORD`, `LIFECYCLE_TEST_PASSWORD`,
`LIFECYCLE_NEXT_PASSWORD`, `EXTERNAL_TEST_PASSWORD`, `FAULT_TEST_PASSWORD`, or
`UNICODE_TEST_PASSWORD`. The scripts fail when a required value is missing.
For the historical CSV tests, use generated alphanumeric passwords without CSV
delimiters. Existing SQL fixture scripts also require values without SQL quotes.

Staging scripts additionally require `PROXY_ADMIN_PASSWORD`,
`LINEAGE_INGRESS_TOKEN`, `CLUSTER_B_CONTROL_PASSWORD`, and
`CLUSTER_B_LINEAGE_TOKEN`. Control passwords rendered into YAML must contain at
least 24 alphanumeric, underscore, or hyphen characters.

These historical scripts target specific test instances and ports. Review their
targets before executing them. Removing credentials from source does not rotate
credentials already installed on a server; rotate those separately if needed.
