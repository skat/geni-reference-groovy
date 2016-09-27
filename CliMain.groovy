@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7.2')
@Grab('oauth.signpost:signpost-core:1.2.1.2')
@Grab('oauth.signpost:signpost-commonshttp4:1.2.1.2')
@Grab('org.codehaus.groovy:groovy-json:2.4.6')
import groovy.io.FileType
import groovy.json.JsonSlurper
import groovyx.net.http.ContentType
import groovyx.net.http.HttpResponseDecorator
import groovyx.net.http.RESTClient

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

class CliMain {
    public static final validCategories = ['udlån', 'indlån', 'prioritetslån', 'pantebreve']
    public static final Map validCategoryAlias = [
            'ud'       : 'udlån',
            'ind'      : 'indlån',
            'prioritet': 'prioritetslån',
            'pant'     : 'pantebreve']
    public static final String defaultBaseUrl = 'https://api.tfe.tse3pindberet.skat.dk'
    OptionAccessor options
    Map context = [:]

    CliMain(args) {
        CliBuilder cli = new CliBuilder(usage: 'indlever [options] <directory>',
                header: 'Options:')
        cli.with {
            h longOpt: 'help', 'Usage information', required: false
            n longOpt: 'dry-run', "Dry run. Do not POST anything"
            b longOpt: 'base-url', args: 1, "Base url, e.g. $defaultBaseUrl"
            c longOpt: 'category', args: 1, "Reporting category. e.g. one of '${validCategories.join("', '")}' or '${validCategoryAlias.keySet().join("', '")}'", required: true
            s longOpt: 'se', args: 1, 'SE number of the reporter', required: true
            p longOpt: 'period', args: 1, 'Period, e.g. "2017"', required: true
            v longOpt: 'verbose', 'Verbose error messages'
            _ longOpt: 'p12', args: 1, 'PKCS12 Key file, .e.g. "~/.oces/indberetter.p12"'
        }
        options = cli.parse(args)
        if (!options) {
            throw new IllegalArgumentException()
        }
        if (options.h) {
            cli.usage()
            throw new IllegalArgumentException()
        }
        if (options.arguments().size != 1) {
            cli.usage()
            throw new IllegalArgumentException("You must provide exactly one directory, not ${options.arguments().size}.")
        }

        if (!validCategories.contains(options.category) && !validCategoryAlias.containsKey(options.category)) {
            throw new IllegalArgumentException("Category cannot be '$options.category'.\n"+
                    "Must be one of '${(validCategories + validCategoryAlias.keySet()).join("', '")}'.")
        }

        if (!(options.period =~ /^[0-9]{4}(-[0-9]{2})?$/)) {
            throw new IllegalArgumentException("Period shall be either a four digit a year ('2017') or year followed by month ('2017-03').")
        }

        if (!options.p12) {
            def pkcs12List = []
            new File("${System.getProperty('user.home')}/.oces").eachFile(FileType.FILES) {
                if (it.name.endsWith('.p12')) {
                    pkcs12List << it.absolutePath
                }
            }
            println "NOTICE: You have not provided a PKCS12 file."
            if (pkcs12List.empty) {
                println "NOTICE: You don't seem to have any OCES keys installed (in '${System.getProperty('user.home')}/.oces')"
            } else {
                println "NOTICE: You have the following OCES keys installed\n${pkcs12List.collect { "NOTICE:     ${it}" }.join("\n")}"
            }
            println ""
        }

        context.dry = options.'dry-run'
        context.baseUrl = options.'base-url' ?: defaultBaseUrl
        context.category = validCategoryAlias.containsKey(options.category) ? validCategoryAlias[options.category] : options.category
        context.se = options.se
        context.period = options.period
        context.p12 = options.p12
        context.verbose = options.v
        context.directory = options.arguments()[0]
    }

    def run() {
        def url = "/${context.category}/pligtige/${context.se}/perioder/${context.period}/konti/"
        println "Indberetter filerne i '${context.directory}' til baseurl '$url'"
        RESTClient restClient = createRestClient()
        new File(context.directory).eachFile { File file ->
            println ""
            String completeUrl = "${url}${file.name}/indleveringer".toString()
            if (context.verbose) println "POST indhold af '${file.name}' til ${completeUrl}"
            def location
            if (!context.dry) {
                restClient.post(
                        path: completeUrl,
                        requestContentType: ContentType.BINARY,
                        body: file.bytes
                ) { response, json ->
                    assert response.status == 201
                    println "Indlevering af '${file.name}' returnerede status kode ${response.statusLine}"
                    location = response.headers.location
                }
            }
            if (!context.dry && location) {
                String decodedUrl = URLDecoder.decode(location, 'UTF-8')
                restClient.get(path: decodedUrl) { HttpResponseDecorator response, json ->
                    assert response.status == 200
                    def slurper = new JsonSlurper().parseText(json.text)
                    println "Status på indleveringen er: ${slurper.data.attributes.status}"

                    if (slurper.data.attributes.status != 'VALID' && context.verbose) {
                        println "Filen '${file.name}' er ugyldig med teksterne:\n  ${slurper.data?.attributes?.beskeder?.join("\n  ")}"
                    }
                }
            }
        }
    }

    RESTClient createRestClient() {
        RESTClient client = new RESTClient(context.baseUrl)
        if (context.p12) {
            Console console = System.console()
            client.auth.certificate(new File(context.p12).toURI().toURL().toString(),
                    console?.readPassword("Enter certificate passphrase: ")?:'' as String)
        }
        // Fejlhåndtering
        client.handler.failure = { HttpResponseDecorator resp, data ->
            if (context.verbose) {
                String headers = resp.headers.each { it -> "${it.name}: ${it.value}" }.join("\n")
                println """Indleveringen fejlede! Detaljeret svar fra serveren:
HTTP Status code: ${resp.status}
Headers:
$headers
Body:
${prettyPrint(toJson(data))}"""
            } else {
                println "Indleveringen fejlede! Serveren svarede: $resp.statusLine"
            }
        }
        return client
    }
}
