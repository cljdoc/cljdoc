# TODO: maybe binding to docker network is sufficient? Dunno
bind_addr = "0.0.0.0"
client_addr = "0.0.0.0"

# run as server
server = true

# We are a single node, it makes automatically assign us as leader:
bootstrap = true

# Questionable, but carried over from command line -ui option
ui_config {
  enabled = true
}
