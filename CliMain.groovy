import groovy.json.JsonBuilder
@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7.2')
@Grab('oauth.signpost:signpost-core:1.2.1.2')
@Grab('oauth.signpost:signpost-commonshttp4:1.2.1.2')
@Grab('org.codehaus.groovy:groovy-json:2.4.6')
import groovy.json.JsonSlurper
import groovyx.net.http.ContentType
import groovyx.net.http.HttpResponseDecorator
import groovyx.net.http.RESTClient
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.Method

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

class CliMain {

    Map context = [:]

    void masseindlevering() {
        printlnVerbose "config: ${context.toMapString()}"
        File indleveringsfil = new File(context.file)
        String s3Path = "/${context.category}/pligtige/${context.se}/${indleveringsfil.name}"
        String apiPath = "/${context.category}/pligtige/${context.se}/perioder/${context.period}/masseindleveringer/"

        printlnVerbose "Uploader '${indleveringsfil.name}' til url '${context.s3InUrl}$s3Path'"
        String location
        String md5
        String urlTilSvarfil
        if (!context.dry) {
            RESTClient restClient = createRestClient(context.s3InUrl)
            def response = restClient.put(
                    path: s3Path,
                    requestContentType: ContentType.BINARY,
                    body: indleveringsfil.bytes
            )

            printlnVerbose "Upload af '${indleveringsfil.name}' returnerede HTTP status ${response.statusLine}"
            assert response.status == 200
            location = response.headers.location
            md5 = response.headers.'Content-MD5'

        }

        JsonBuilder requestJson = new JsonBuilder()
        requestJson.data {
            attributes {
                s3Key "${context.s3InUrl}$s3Path"
                s3Md5Checksum md5
            }
        }

        printlnVerbose "Aktiverer masseindlevering '${context.s3InUrl}$s3Path' på url '${context.baseUrl}$apiPath'"
        if (!context.dry && location) {
            RESTClient restClient = createRestClient(context.baseUrl)
            restClient.post(
                    path: apiPath,
                    requestContentType: 'application/json',
                    body: requestJson.toString()
            ) { response, json ->
                printlnVerbose "Aktivering af '${context.s3InUrl}$s3Path' returnerede HTTP status ${response.statusLine}"
                assert response.status == 201
                location = response.headers.location
                String jsontxt = json.text
                def slurper = new JsonSlurper().parseText(jsontxt)
                println "Status på masseindleveringen er: ${slurper.data.attributes.status}"
                urlTilSvarfil = slurper.data.links.svarfil
                printlnVerbose "Svar på aktivering: ${jsontxt}"
            }
        }

        printlnVerbose "Henter urlTilSvarfil fra url '${context.s3OutUrl}$s3Path'"
        if (!context.dry && urlTilSvarfil) {
            String status
            InputStream stream
            String output = context.output
            HTTPBuilder http = new HTTPBuilder(context.s3OutUrl)
            http.request(Method.GET, ContentType.BINARY) { req ->
                uri.path = s3Path
                response.'200' = { resp, binary ->
                    status = resp.status
                    stream = binary
                    if (output) {
                        File outfile = new File(output)
                        outfile.delete()
                        outfile << stream
                    }
                }
                response.failure = { resp ->
                    status = resp.status
                }
            }
            printlnVerbose "Download urlTilSvarfil '${context.s3OutUrl}$s3Path' returnerede HTTP status '${status}'"
            assert status == '200'
            println "Masseindlevering gennemført. Svarfil '${context.s3OutUrl}$s3Path' blev gemt lokalt her: ${context.output}"
        }
    }

    void enkeltindlevering() {
        printlnVerbose "config: ${context.toMapString()}"
        def path = "/${context.category}/pligtige/${context.se}/perioder/${context.period}/konti/"
        def dir = new File(context.directory)
        println "Indberetter ${dir.fileCount} filer i '${context.directory}' til baseurl '$path'"
        RESTClient restClient = createRestClient(context.baseUrl)
        dir.eachFile { File file ->
            String completeUrl = "${path}${file.name}/indleveringer".toString()
            if (context.kontoidlength > 0 && file.name.size() > context.kontoidlength) {
                println "KontoID er for langt, eller filnavn '${file.name}' svarer ikke til KontoID. Max længde ${context.kontoidlength}"
            } else {
                printlnVerbose "POST indhold af '${file.name}' til ${completeUrl}"
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
    }

    RESTClient createRestClient(String baseUrl) {
        RESTClient client = new RESTClient(baseUrl)
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

    void printlnVerbose(String tekst){
        if(context.verbose)println(tekst)
    }
}

