#!/usr/bin/env bash
set -eux

echo $PGP_SECRET | base64 --decode | gpg --import --no-tty --batch --yes
export GPG_TTY=$(tty)

./mill thera.publish \
  --sonatypeCreds $SONATYPE_USER:$SONATYPE_PW \
  --gpgArgs --passphrase=$PGP_PASSPHRASE,--pinentry-mode=loopback,--batch,--yes,-a,-b \
  --readTimeout 600000 \
  --release true \
  --signed true
