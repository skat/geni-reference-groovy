@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7')
@Grab('oauth.signpost:signpost-core:1.2.1.2')
@Grab('oauth.signpost:signpost-commonshttp4:1.2.1.2')
import groovyx.net.http.ContentType
import groovyx.net.http.RESTClient

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
        context.baseUrl = options.'base-url' ?: 'http://api.geni.skat.dk'
        context.domain = options.domain
        context.cvr = options.cvr
        context.period = options.period
        context.directory = options.arguments()[0]
    }

    def run() {
        def url = "${context.baseUrl}/${context.domain}/cvr:${context.cvr}/${context.period}/indleveringer/"
        RESTClient indlevering = new RESTClient(url)
        new File(context.directory).eachFile {
            println "POST $url${it.name[0..-5]} content of ${it.name}"
            if (!context.dry) {
                indlevering.post(
                        path: "${it.name[0..-5]}",
                        requestContentType: ContentType.XML,
                        body: it.bytes
                ).status == 201 || {
                    println "Indlevering af '${it.name}' fejlede med status kode ${response.statusLine}"
                }
            }
            println "GET $url${it.name[0..-5]}/status"
            if (!context.dry) {
                indlevering.get(path: "${it.name[0..-5]}/status") { response, json ->
                    assert response.status == 200
                    if (json.valid != 'true') {
                        println "${it.name} er ugyldig med teksterne\n  ${json.error*.description.join("\n  ")}"
                    }
                }
            }
        }

    }
}
