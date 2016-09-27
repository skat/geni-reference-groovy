#!/usr/bin/env groovy
import java.util.logging.Level
import java.util.logging.Logger

CliMain indlever
try {
    Logger.getLogger('').setLevel(Level.OFF) //top logger
    indlever = new CliMain(args)
} catch (IllegalArgumentException e) {
    println e.message ?: ''
    System.exit 1
}
indlever.run()
