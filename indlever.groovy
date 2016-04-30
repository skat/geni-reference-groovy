#!/usr/bin/env groovy
CliMain indlever
try {
    indlever = new CliMain(args)
} catch (IllegalArgumentException e) {
    System.exit 1
}
indlever.run()
