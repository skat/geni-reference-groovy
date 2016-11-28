#!/usr/bin/env groovy
import java.util.logging.Level
import java.util.logging.Logger

try {
    Logger.getLogger('').setLevel(Level.OFF) //stands top logger
    Map context = CliHelper.parseOptions(args)
    try {
        new CliMain(context: context).with {
            context.masseindlevering ? masseindlevering() : enkeltindlevering()
        }
    } catch (Exception e) {
        e.printStackTrace()
        System.exit 1
    }
} catch (IllegalArgumentException e) {
    println e.message ?: ''
    System.exit 1
}



