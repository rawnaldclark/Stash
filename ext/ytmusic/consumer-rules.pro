# The YouTube Music extension's own keep rules, applied only where this module is
# consumed. Nothing here is looked up by name at runtime: the parsers read JSON into
# kotlinx-serialization models, whose generated serializers R8 already keeps, and the
# Compose functions are ordinary code.
