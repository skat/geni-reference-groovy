class CliMain {
    OptionAccessor options
    String directory

    CliMain(args) {
        CliBuilder cli = new CliBuilder(usage: 'indlever [options] <directory>',
                header: 'Options:')
        cli.with {
            h longOpt: 'help', 'Usage information'
            b longOpt: 'base-url', 'Base url, e.g. http://api.geni.skat.dk'
            p longOpt: 'path', 'Path to resource, e.g. "/Udlån/cvr:<>/2017/indleveringer"'
        }
        options = cli.parse(args)
        if (options.arguments().size != 1) {
            cli.usage()
            throw new IllegalArgumentException()
        }
        if (options.help) {
            cli.usage()
            return
        }
        directory = options.arguments()[0]
    }

    def run() {
    }
}
