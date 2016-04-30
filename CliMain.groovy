@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7')
@Grab('oauth.signpost:signpost-core:1.2.1.2')
@Grab('oauth.signpost:signpost-commonshttp4:1.2.1.2')

import groovyx.net.http.RESTClient
import static groovyx.net.http.ContentType.*

class CliMain {
    OptionAccessor options
    LinkedHashMap context = [:]

    CliMain(args) {
        CliBuilder cli = new CliBuilder(usage: 'indlever [options] <directory>',
                header: 'Options:')
        cli.with {
            h longOpt: 'help', 'Usage information'
            b longOpt: 'base-url', args: 1, 'Base url, e.g. http://api.geni.skat.dk'
            p longOpt: 'path', args: 1, 'Path to resource, e.g. "/Udlån/cvr:<>/2017/indleveringer"'
        }
        options = cli.parse(args)
        if (options.arguments().size != 1) {
            cli.usage()
            throw new IllegalArgumentException("You must provide exactly one directory, not ${options.arguments().size }.")
        }
        if (options.help) {
            cli.usage()
            return
        }
        context.'base-url' = options.'base-url'?: 'http://api.geni.skat.dk'
        context.path = options.path
        context.directory = options.arguments()[0]
    }

    def run() {
        def indlevering = new RESTClient("${context.'base-url'}${context.path}")
    }
}
