@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7')
@Grab('oauth.signpost:signpost-core:1.2.1.2')
@Grab('oauth.signpost:signpost-commonshttp4:1.2.1.2')
import groovyx.net.http.ContentType
import groovyx.net.http.HttpResponseDecorator
import groovyx.net.http.RESTClient

class CliMain {
    public static final validDomains = ['Udlån', 'Indlån', 'Prioritetslån', 'Pantebrev']
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

        if (!validDomains.contains(options.domain)) {
            throw new IllegalArgumentException("Domain cannot be '$options.domain'. Must be one of '${validDomains.join("', '")}'.")
        }

        if(!(options.period =~ /^[0-9]{4}(-[0-9]{2})?$/)){
            throw new IllegalArgumentException("Period shall be either a four digit a year ('2017') or year followed by month ('2017-03').")
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
        def url = "${context.baseUrl}/${context.domain}/pligtige/cvr:${context.cvr}/perioder/${context.period}/konti/"
        RESTClient indlevering = new RESTClient(url)
        new File(context.directory).eachFile {
            println "POST $url${it.name[0..-5]}/indleveringer content of ${it.name}"
            def location
            if (!context.dry) {
                indlevering.post(
                        path: "${it.name[0..-5]}/indleveringer",
                        requestContentType: ContentType.XML,
                        body: it.bytes
                ) { response ->
                    response.status == 201 || {
                        println "Indlevering af '${it.name}' fejlede med status kode ${response.statusLine}"
                    }
                    location = response.headers.location
                }
            }
            println "GET $location/status"
            if (!context.dry) {
                indlevering.get(path: "$location/status") { HttpResponseDecorator response, json ->
                    assert response.status == 200
                    if (json.valid != 'true') {
                        println "${it.name} er ugyldig med teksterne\n  ${json.error*.description.join("\n  ")}"
                    }
                }
            }
        }

    }
}
