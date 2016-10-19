@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7.2')
@Grab('oauth.signpost:signpost-core:1.2.1.2')
@Grab('oauth.signpost:signpost-commonshttp4:1.2.1.2')
@Grab('org.codehaus.groovy:groovy-json:2.4.6')
import groovy.json.JsonSlurper
import groovyx.net.http.ContentType
import groovyx.net.http.HttpResponseDecorator
import groovyx.net.http.RESTClient

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

class CliMain {

    Map context = [:]

    void run() {
        def url = "/${context.category}/pligtige/${context.se}/perioder/${context.period}/konti/"
        def dir = new File(context.directory)
        println "Indberetter ${dir.fileCount} filer i '${context.directory}' til baseurl '$url'"
        RESTClient restClient = createRestClient()
        dir.eachFile { File file ->
            println ""
            String completeUrl = "${url}${file.name}/indleveringer".toString()
            if (context.verbose) println "POST indhold af '${file.name}' til ${completeUrl}"
            def location
            if (!context.dry) {
                // POST indlevering
                restClient.post(
                        path: completeUrl,
                        requestContentType: ContentType.BINARY,
                        body: file.bytes
                ) { response, json ->
                    assert response.status == 201
                    println "Indlevering af '${file.name}' returnerede HTTP status ${response.statusLine}"
                    location = response.headers.location
                }
            }
            if (!context.dry && location) {
                // GET status på indlevering
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
                                    (console?.readPassword("Enter certificate passphrase: ")?:'') as String)
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
                println "Indleveringen fejlede med HTTP status: $resp.statusLine"
            }
        }
        return client
    }
}
