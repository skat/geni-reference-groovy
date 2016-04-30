@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7')
@Grab('oauth.signpost:signpost-core:1.2.1.2')
@Grab('oauth.signpost:signpost-commonshttp4:1.2.1.2')

import groovyx.net.http.RESTClient
import org.apache.http.entity.ContentType

class CliMain {
    OptionAccessor options
    LinkedHashMap context = [:]

    CliMain(args) {
        CliBuilder cli = new CliBuilder(usage: 'indlever [options] <directory>',
                header: 'Options:')
        cli.with {
            h longOpt: 'help', 'Usage information'
            n longOpt: 'dry-run', "Dry run. Do not POST anything"
            b longOpt: 'base-url', args: 1, 'Base url, e.g. http://api.geni.skat.dk'
            d longOpt: 'domain', args: 1, 'Reporting domain. e.g. "Udlån"', required: true
            c longOpt: 'cvr', args: 1, 'CVR number', required: true
            p longOpt: 'period', args: 1, 'Period, e.g. "2017"', required: true
        }
        options = cli.parse(args)
        if (options.arguments().size != 1) {
            cli.usage()
            throw new IllegalArgumentException("You must provide exactly one directory, not ${options.arguments().size}.")
        }
        if (options.help) {
            cli.usage()
            return
        }
        context.dry = options.'dry-run'
        context.'base-url' = options.'base-url' ?: 'http://api.geni.skat.dk'
        context.domain = options.domain
        context.cvr = options.cvr
        context.period = options.period
        context.directory = options.arguments()[0]
    }

    def run() {
        def url = "${context.'base-url'}/${context.domain}/cvr:${context.cvr}/${context.period}/indleveringer"
        RESTClient indlevering = new RESTClient(url)
        new File(context.directory).eachFile {
            println "POST $url/${it.name[0..-5]} content of ${it.name}"
            if (!context.dry) {
                indlevering.post(
                        path: "/${it.name[0..-5]}",
                        requestContentType: ContentType.APPLICATION_XML,
                        body: it.getBytes()
                )
            }
        }
    }
}
