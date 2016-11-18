#!/usr/bin/env groovy
import java.util.logging.Level
import java.util.logging.Logger

try {
    Logger.getLogger('').setLevel(Level.OFF) //stands top logger
    Map context = CliHelper.parseOptions(args)
    if(context.masseindlevering){
        new CliMain(context: context).masseindlevering()
    }
    else {
        new CliMain(context: context).enkeltindlevering()
    }
} catch (Exception e) {
    println e.message ?: ''
    System.exit 1
}



