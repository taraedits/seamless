#!/usr/bin/env bash
sbt fastOptJS
OUTPUT_FILE=../webapp/src/engine/domain.js
rm -rf $OUTPUT_FILE

touch $OUTPUT_FILE

echo "/* eslint-disable */\n" >> $OUTPUT_FILE
cat target/scala-2.12/seamless-ddd-fastopt.js >> $OUTPUT_FILE

echo "domain logic written to $OUTPUT_FILE"