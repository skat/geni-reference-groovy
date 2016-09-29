#!/usr/bin/env groovy
import java.util.logging.Level
import java.util.logging.Logger

try {
    Logger.getLogger('').setLevel(Level.OFF) //stands top logger
    Map context = CliHelper.parseOptions(args)
    new CliMain(context: context).run()
} catch (Exception e) {
    println e.message ?: ''
    System.exit 1
}



