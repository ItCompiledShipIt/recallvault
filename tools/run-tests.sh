#!/bin/bash
# JVM test suite for RecallVault pure classes.
# Usage: tools/run-tests.sh
set -e
cd "$(dirname "$0")/.."
AJ=$(ls ~/workspace/.toolchains/android-sdk/platforms/*/android.jar | sort | tail -1)
JAVAC=~/workspace/.toolchains/jdk-25.0.4.1+1/bin/javac
JAVA=~/workspace/.toolchains/jdk-25.0.4.1+1/bin/java
rm -rf /tmp/rvtest && mkdir -p /tmp/rvtest
$JAVAC -cp "$AJ:tools/json-test.jar" -d /tmp/rvtest \
  java/com/irwinlabs/recallvault/Extract.java java/com/irwinlabs/recallvault/Item.java \
  java/com/irwinlabs/recallvault/Ask.java java/com/irwinlabs/recallvault/Patterns.java \
  java/com/irwinlabs/recallvault/PlainExport.java java/com/irwinlabs/recallvault/Resurface.java \
  java/com/irwinlabs/recallvault/Links.java java/com/irwinlabs/recallvault/IngestSeen.java \
  java/com/irwinlabs/recallvault/Digest.java java/com/irwinlabs/recallvault/Actions.java \
  java/com/irwinlabs/recallvault/Expiry.java java/com/irwinlabs/recallvault/Entities.java \
  java/com/irwinlabs/recallvault/FollowUp.java java/com/irwinlabs/recallvault/NearDupes.java \
  java/com/irwinlabs/recallvault/Report.java java/com/irwinlabs/recallvault/Recap.java \
  java/com/irwinlabs/recallvault/Forecast.java java/com/irwinlabs/recallvault/HeadsUp.java \
  java/com/irwinlabs/recallvault/Snooze.java java/com/irwinlabs/recallvault/Receipts.java \
  java/com/irwinlabs/recallvault/Facts.java java/com/irwinlabs/recallvault/Checkup.java \
  java/com/irwinlabs/recallvault/WebSearch.java \
  java/com/irwinlabs/recallvault/MailFilter.java \
  java/com/irwinlabs/recallvault/Privacy.java java/com/irwinlabs/recallvault/DayGroup.java java/com/irwinlabs/recallvault/Skills.java java/com/irwinlabs/recallvault/SmsFilter.java java/com/irwinlabs/recallvault/NotifyFilter.java java/com/irwinlabs/recallvault/FactMiner.java \
  java/com/irwinlabs/recallvault/Embed.java \
  java/com/irwinlabs/recallvault/Db.java java/com/irwinlabs/recallvault/Hashes.java \
  java/com/irwinlabs/recallvault/VaultCrypto.java \
  java/com/irwinlabs/recallvault/AudioDecode.java java/com/irwinlabs/recallvault/Backup.java \
  tools/stubs/LocalEmbed.java tools/TestMain.java
$JAVA -cp "/tmp/rvtest:tools/json-test.jar:$AJ" TestMain
