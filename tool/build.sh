#!/usr/bin/env bash
set -e
rm -rf build
./gradlew distZip --refresh-dependencies
cp build/distributions/*.zip ../../files/modbus.zip
