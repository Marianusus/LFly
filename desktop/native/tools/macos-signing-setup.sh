#!/bin/sh
# Once per Mac, for local builds: a self-signed code-signing certificate, "LFly Local Signing",
# in the login keychain. macOS keeps permissions (Local Network, microphone…) per signature, and an
# ad hoc signature changes with every build, so each new build asked again. With this certificate
# macos-bundle.sh signs every build the same way and the answer is remembered.
# The keychain password is asked here (or read from standard input); nothing is stored.
set -e
NAME="LFly Local Signing"
KC="$HOME/Library/Keychains/login.keychain-db"
if security find-certificate -c "$NAME" "$KC" >/dev/null 2>&1; then
    echo "\"$NAME\" is already there."
    exit 0
fi
D=$(mktemp -d)
trap 'rm -rf "$D"' EXIT
cat > "$D/o.cnf" <<CNF
[req]
distinguished_name=dn
x509_extensions=ext
prompt=no
[dn]
CN=$NAME
[ext]
basicConstraints=critical,CA:false
keyUsage=critical,digitalSignature
extendedKeyUsage=critical,codeSigning
CNF
/usr/bin/openssl req -x509 -newkey rsa:2048 -keyout "$D/k.pem" -out "$D/c.pem" -days 3650 -nodes -config "$D/o.cnf" 2>/dev/null
P=$(/usr/bin/openssl rand -hex 12)
/usr/bin/openssl pkcs12 -export -inkey "$D/k.pem" -in "$D/c.pem" -out "$D/l.p12" -passout "pass:$P" -name "$NAME" 2>/dev/null
# In a terminal the password is typed unseen; otherwise it is read from standard input
# (`echo … | ssh mac sh macos-signing-setup.sh`).
if [ -t 0 ]; then
    printf "Password of this Mac (to unlock the keychain): "
    stty -echo; read -r KP; stty echo; echo
else
    read -r KP
fi
security unlock-keychain -p "$KP" "$KC"
security import "$D/l.p12" -k "$KC" -P "$P" -T /usr/bin/codesign
# codesign may use the key without a dialog each time.
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KP" "$KC" >/dev/null
echo "Done: \"$NAME\". Build again with tools/macos-bundle.sh."
