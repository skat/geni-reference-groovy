@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7')
@Grab('oauth.signpost:signpost-core:1.2.1.2')
@Grab('oauth.signpost:signpost-commonshttp4:1.2.1.2')
import groovy.io.FileType
import groovyx.net.http.ContentType
import groovyx.net.http.HttpResponseDecorator
import groovyx.net.http.RESTClient
import org.apache.http.conn.scheme.Scheme
import org.apache.http.conn.ssl.AllowAllHostnameVerifier
import org.apache.http.conn.ssl.SSLSocketFactory
import java.security.KeyStore

class CliMain {
    public static final validDomains = ['udlån', 'indlån', 'prioritetslån', 'pantebrev']
    public static final String defaultBaseUrl = 'https://api.geni.skat.dk'
    OptionAccessor options
    LinkedHashMap context = [:]

    CliMain(args) {
        CliBuilder cli = new CliBuilder(usage: 'indlever [options] <directory>',
                header: 'Options:')
        cli.with {
            h longOpt: 'help', 'Usage information'
            n longOpt: 'dry-run', "Dry run. Do not POST anything"
            b longOpt: 'base-url', args: 1, "Base url, e.g. $defaultBaseUrl"
            d longOpt: 'domain', args: 1, 'Reporting domain. e.g. "udlån"', required: true
            c longOpt: 'cvr', args: 1, 'CVR number', required: true
            p longOpt: 'period', args: 1, 'Period, e.g. "2017"', required: true
            _ longOpt: 'p12', args: 1, 'PKCS12 Key file, .e.g. "~/.oces/indberetter.p12"'
        }
        options = cli.parse(args)
        if (options.arguments().size != 1) {
            cli.usage()
            throw new IllegalArgumentException("You must provide exactly one directory, not ${options.arguments().size}.")
        }

        if (!validDomains.contains(options.domain)) {
            throw new IllegalArgumentException("Domain cannot be '$options.domain'. Must be one of '${validDomains.join("', '")}'.")
        }

        if (!(options.period =~ /^[0-9]{4}(-[0-9]{2})?$/)) {
            throw new IllegalArgumentException("Period shall be either a four digit a year ('2017') or year followed by month ('2017-03').")
        }

        if (!options.p12) {
            def pkcs12List = []
            new File("${System.getProperty('user.home')}/.oces").eachFile(FileType.FILES) {
                if (it.name.endsWith('.p12')) { pkcs12List << it.absolutePath }
            }
            println "NOTICE: You have not provided a PKCS12 file."
            if (pkcs12List.empty) {
                println "NOTICE: And you don't seem to have any OCES keys installed (in '${System.getProperty('user.home')}/.oces')"
            } else {
                println "NOTICE: You have the following OCES keys installed\n${pkcs12List.collect { "NOTICE:     ${it}" }.join("\n")}"
            }
            println ""
        }

        if (options.help) {
            cli.usage()
            return
        }
        context.dry = options.'dry-run'
        context.baseUrl = options.'base-url' ?: defaultBaseUrl
        context.domain = options.domain
        context.cvr = options.cvr
        context.period = options.period
        context.p12 = options.p12
        context.directory = options.arguments()[0]
    }

    def run() {
        def url = "/${context.domain}/pligtige/${context.cvr}/perioder/${context.period}/konti/"
        new File(context.directory).eachFile {
            println "POST ${context.domain}${url}${it.name[0..-5]}/indleveringer content of ${it.name}"
            def location
            println("${url}${it.name[0..-5]}/indleveringer")
            if (!context.dry) {
                restClient.post(
                        path: "${url}${it.name[0..-5]}/indleveringer",
                        requestContentType: ContentType.XML,
                        body: it.bytes
                ) { response ->
                    response.status == 201 || {
                        println "Indlevering af '${it.name}' fejlede med status kode ${response.statusLine}"
                    }
                    location = response.headers.location
                }
            }
            println "${context.baseUrl}${location}"
            if (!context.dry) {
                String decodedUrl = URLDecoder.decode(location, 'UTF-8')
                restClient.get(path: decodedUrl){ HttpResponseDecorator response, json ->
                    assert response.status == 200
                    if (json.valid != 'true') {
                        println "${it.name} er ugyldig med teksterne\n  ${json.data?.beskeder?.join("\n  ")}"
                    }
                }
            }
        }
    }

    RESTClient getRestClient(){
        RESTClient client = new RESTClient(context.baseUrl)
        if (context.p12) {
            client.client.connectionManager.schemeRegistry.register(getScheme(context.p12))
        }
        return client
    }

    static Scheme getScheme(String certFilename) {
        KeyStore keyStore = KeyStore.getInstance('PKCS12')
        keyStore.load(new FileInputStream(certFilename))
        SSLSocketFactory socketFactory = new SSLSocketFactory(keyStore)
        socketFactory.hostnameVerifier = new AllowAllHostnameVerifier()
        return new Scheme("https", socketFactory, 443)
    }
}
