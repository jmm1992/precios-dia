#!/usr/bin/env bash
# Publica Precios Día: arma la app, la deja en apk/ (con su versión) y sube todo a GitHub Pages.
#   bash publicar.sh "qué cambió"
# La página se actualiza sola en los celulares; la app (si cambió android/) se instala sola al salir de ella.
# Antes de publicar un cambio de la app: subir versionCode en android/app/build.gradle.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.20.8-hotspot"
(cd android && ./gradlew.bat assembleRelease --offline -q)
mkdir -p apk
cp android/app/build/outputs/apk/release/app-release.apk apk/precios-dia.apk
sed -n 's/^\s*versionCode \([0-9]*\).*/\1/p' android/app/build.gradle > apk/version.txt
cp apk/precios-dia.apk "/c/Users/JuanM/Desktop/Precios Día - para instalar/Precios Dia.apk" 2>/dev/null || true
echo "versión $(cat apk/version.txt)"
git add -A
git -c user.name="Juan Manuel" -c user.email="tvargoficial@gmail.com" commit -q -m "${1:-Actualización}

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" || true
git -c credential.helper= -c "credential.helper=!'C:/Program Files/GitHub CLI/gh.exe' auth git-credential" push -q
echo "publicado"
