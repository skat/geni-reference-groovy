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
    LinkedHashMap context = [:]

    CliMain(args) {
        CliBuilder cli = new CliBuilder(usage: 'indlever [options] <directory>',
                header: 'Options:')
        cli.with {
            h longOpt: 'help', 'Usage information'
            n longOpt: 'dry-run', "Dry run. Do not POST anything"
            b longOpt: 'base-url', args: 1, "Base url, e.g. $defaultBaseUrl"
            c longOpt: 'category', args: 1, 'Reporting category. e.g. "udlån"', required: true
            s longOpt: 'se', args: 1, 'SE number of the reporter', required: true
            p longOpt: 'period', args: 1, 'Period, e.g. "2017"', required: true
            _ longOpt: 'p12', args: 1, 'PKCS12 Key file, .e.g. "~/.oces/indberetter.p12"'
        }
        options = cli.parse(args)
        if (!options) {
            throw new IllegalArgumentException()
        }
        if (options.help) {
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
        context.directory = options.arguments()[0]
    }

    def run() {
        def url = "/${context.category}/pligtige/${context.se}/perioder/${context.period}/konti/"
        RESTClient restClient = createRestClient()
        new File(context.directory).eachFile {
            println "POST ${context.category}${url}${it.name[0..-5]}/indleveringer content of ${it.name}"
            def location
            println("${url}${it.name[0..-5]}/indleveringer")
            if (!context.dry) {
                restClient.post(
                        path: "${url}${it.name[0..-5]}/indleveringer",
                        requestContentType: ContentType.BINARY,
                        body: it.bytes
                ) { response, json ->
                    assert response.status == 201
                    println "Indlevering af '${it.name}' fejlede med status kode ${response.statusLine}"
                    location = response.headers.location
                }
            }
            println "${context.baseUrl}${location}"
            if (!context.dry) {
                String decodedUrl = URLDecoder.decode(location, 'UTF-8')
                restClient.get(path: decodedUrl) { HttpResponseDecorator response, json ->
                    assert response.status == 200
                    String text = json.text
                    def slurper = new JsonSlurper().parseText(text)
                    println("Status på indleveringen er: ${slurper.data.attributes.status}")

                    if (slurper.data.attributes.status != 'VALID') {
                        println "${it.name} Er ugyldig med teksterne\n  ${slurper.data?.attributes?.beskeder?.join("\n  ")}"
                    }
                }
            }
        }
    }

    RESTClient createRestClient() {
        RESTClient client = new RESTClient(context.baseUrl)
        if (context.p12) {
            Console console = System.console();
            client.auth.certificate(new File(context.p12).toURI().toURL().toString(),
                    console?.readPassword("Enter certificate passphrase: ")?:'' as String)
        }
        client.handler.failure = { HttpResponseDecorator resp, data ->
            String headers = resp.headers.each { it -> "${it.name}: ${it.value}" }.join("\n")
            throw new RuntimeException("\nHTTP Status code:${resp.status}\n" +
                    "$headers\n" +
                    "Body:\n${prettyPrint(toJson(data))}")
        }
        return client
    }
}
