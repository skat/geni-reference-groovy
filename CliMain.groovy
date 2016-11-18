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
        File file = new File(context.file)
        String s3Url = "/${context.category}/pligtige/${context.se}/${file.name}"
        String apiUrl = "/${context.category}/pligtige/${context.se}/perioder/${context.period}/masseindleveringer/"

        if (context.verbose) println "Uploader '${file.name}' til url '${context.s3InUrl}$s3Url'"
        String location
        String md5
        String svarfil
        if (!context.dry) {
            RESTClient restClient = createRestClient(context.s3InUrl)
            def response = restClient.put(
                    path: s3Url,
                    requestContentType: ContentType.BINARY,
                    body: file.bytes
            )

            if (context.verbose) println "Upload af '${file.name}' returnerede HTTP status ${response.statusLine}"
            assert response.status == 200
            location = response.headers.location
            md5 = response.headers.'Content-MD5'

        }

        JsonBuilder requestJson = new JsonBuilder()
        requestJson.data {
            attributes {
                s3Key "${context.s3InUrl}$s3Url"
                s3Md5Checksum md5
            }
        }

        if (context.verbose) println "Aktiverer masseindlevering '${context.s3InUrl}$s3Url' på url '${context.baseUrl}$apiUrl'"
        if (!context.dry && location) {
            RESTClient restClient = createRestClient(context.baseUrl)
            restClient.post(
                    path: apiUrl,
                    requestContentType: 'application/json',
                    body: requestJson.toString()
            ) { response, json ->
                if (context.verbose) println "Aktivering af '${context.s3InUrl}$s3Url' returnerede HTTP status ${response.statusLine}"
                assert response.status == 201
                location = response.headers.location
                def slurper = new JsonSlurper().parseText(json.text)
                println "Status på masseindleveringen er: ${slurper.data.attributes.status}"
                svarfil = slurper.data.links.svarfil
            }
        }

        if (context.verbose) println "Henter svarfil fra url '${context.s3OutUrl}$s3Url'"
        if (!context.dry && svarfil) {
            String status
            InputStream stream
            String output = context.output
            HTTPBuilder http = new HTTPBuilder(context.s3OutUrl)
            http.request(Method.GET, ContentType.BINARY) {req ->
                uri.path = s3Url
                response.'200' = {resp, binary ->
                    status = resp.status
                    stream = binary
                    if(output){
                        File outfile = new File(output)
                        outfile.delete()
                        outfile << stream
                    }
                }
                response.failure = {resp ->
                    status = resp.status
                }
            }
            if (context.verbose) println "Download svarfil '${context.s3OutUrl}$s3Url' returnerede HTTP status '${status}'"
            assert status == '200'
            println "Masseindlevering gennemført"
        }
    }


    void enkeltindlevering() {
        def url = "/${context.category}/pligtige/${context.se}/perioder/${context.period}/konti/"
        def dir = new File(context.directory)
        println "Indberetter ${dir.fileCount} filer i '${context.directory}' til baseurl '$url'"
        RESTClient restClient = createRestClient(context.baseUrl)
        dir.eachFile { File file ->
            println ""
            String completeUrl = "${url}${file.name}/indleveringer".toString()
            if(context.kontoidlength > 0 && file.name.size() > context.kontoidlength){
                println "KontoID er for langt, eller filnavn '${file.name}' svarer ikke til KontoID. Max længde ${context.kontoidlength}"
            }
            else {
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
}
