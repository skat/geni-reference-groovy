
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovyx.net.http.*

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7.2')
@Grab('oauth.signpost:signpost-core:1.2.1.2')
@Grab('oauth.signpost:signpost-commonshttp4:1.2.1.2')
@Grab('org.codehaus.groovy:groovy-json:2.4.6')
@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7.2')
@Grab('oauth.signpost:signpost-core:1.2.1.2')
@Grab('oauth.signpost:signpost-commonshttp4:1.2.1.2')
@Grab('org.codehaus.groovy:groovy-json:2.4.6')
class RenteClient {

    static final MAX_KONTO_ID_LENGTH = 30
    static final String JSON_API_HEADER = "application/vnd.api+json"

    Map context = [:]

    String certificatePassword

    void masseindlevering() {
        try {
            printlnVerbose "config: ${context.toMapString()}"

            File indleveringsfil = createZip(context.directory)

            String generatedS3Key = generateS3Key(context.period)

            String s3Path = "/${context.category}/pligtige/${context.se}/${generatedS3Key}"
            String apiPath = "/${context.category}/pligtige/${context.se}/perioder/${context.period}/masseindleveringer/"


            if (!context.dry) {
                printlnVerbose "Uploader '${indleveringsfil.name}' til url '${context.s3InUrl}$s3Path'"
                S3UploadReusult s3UploadReusult = uploadToS3In(s3Path, indleveringsfil)
                printlnVerbose "Aktiverer masseindlevering '${context.s3InUrl}$s3Path' på url '${context.baseUrl}$apiPath'"
                String jsonBody = createRequestJsonBody(s3UploadReusult)
                String urlTilSvarfil = aktiverMasseindlevering(apiPath, jsonBody)
                if (urlTilSvarfil) {
                    printlnVerbose "Henter svarfil fra url '${context.s3OutUrl}$s3Path'"
                    downloadAndUnzipSvarfilFromS3(urlTilSvarfil, s3Path)
                    println "Masseindlevering gennemført. Svarfiler blev udpakket lokalt her: ${new File(context.outdir).absolutePath}"
                    println("Hav en god dag :)")
                }
            } else {
                println('Ingen upload foretages, da dette er dry run')
            }
        }
        catch (IllegalStateException e){
            println e.message
            println "masseindlevering fejlede"
        }
    }

    void enkeltindlevering() {
        printlnVerbose "config: ${context.toMapString()}"
        def path = "/${context.category}/pligtige/${context.se}/perioder/${context.period}/konti/"
        def dir = new File(context.directory)
        println "Indberetter ${dir.listFiles().size()} filer i '${context.directory}' til '${context.baseUrl}$path'"
        RESTClient restClient = createRestClient(context.baseUrl)
        findAllExceptHiddenAndDirectories(dir.path).each { File file ->
            String completeUrl = "${path}${file.name}/indleveringer".toString()
            if (file.name.size() > MAX_KONTO_ID_LENGTH) {
                println "KontoID '${file.name}' er for langt. Max længde er ${MAX_KONTO_ID_LENGTH}"
            } else {
                printlnVerbose "POST indhold af '${file.name}' til ${completeUrl}"
                def location
                if (!context.dry) {
                    // POST indlevering
                    restClient.post(
                            headers: ['Accept': JSON_API_HEADER],
                            path: completeUrl,
                            requestContentType: ContentType.BINARY,
                            body: file.bytes
                    ) { response, json ->
                        assertResponseStatus(response, 201)
                        println "Indlevering af '${file.name}' returnerede HTTP status ${response.statusLine}"
                        location = response.headers.location
                    }
                }
                if (!context.dry && location) {
                    // GET status på indlevering
                    String decodedUrl = URLDecoder.decode(location, 'UTF-8')
                    restClient.get(path: decodedUrl, headers: ['Accept': JSON_API_HEADER]) { HttpResponseDecorator response, json ->
                        assertResponseStatus(response, 200)
                        def slurper = new JsonSlurper().parseText(json.text)
                        def status = slurper.data.attributes."renteIndberetningTilbagemelding"."tilbagemeldingOplysninger"."indberetningValideringStatus"
                        println "Status på indleveringen er: ${status}"
                        if (status != 'VALID' && context.verbose) {
                            println "Filen '${file.name}' er ugyldig med teksterne:\n  ${slurper.data?.attributes?.beskeder?.join("\n  ")}"
                        }
                    }
                }
            }
        }
    }

    protected void addErrorHandlingForClient(HTTPBuilder client) {
        client.handler.failure = { HttpResponseDecorator resp, data ->
            if (context.verbose) {
                String headers = resp.headers.each { it -> "${it.name}: ${it.value}" }.join("\n")
                println """HTTP-kald fejlede! Detaljeret svar fra serveren:
HTTP Status code: ${resp.status}
Headers:
$headers
Body:
${data}"""
            } else {
                println "Indleveringen fejlede med HTTP status: $resp.statusLine"
            }
        }
    }

    protected void downloadAndUnzipSvarfilFromS3(String urlTilSvarfil, String s3Path) {
        InputStream stream
        HTTPBuilder http = createHttpClient(urlTilSvarfil)

        http.request(Method.GET, ContentType.BINARY) { req ->
            uri.path = s3Path
            response.'200' = { response, binary ->
                stream = binary
                File.createTempFile('svar', '.zip').with { File outfile ->
                    printlnVerbose "Download urlTilSvarfil '${this.context.s3OutUrl}$s3Path' returnerede HTTP status '${response.status}'"
                    outfile.deleteOnExit()
                    outfile << stream
                    unzip(outfile, this.context.outdir)
                }
            }
            response.failure = { response ->
                int status = response.status
                throw new IllegalStateException("Statuskoden var $status, men 200 var forventet")
            }
        }
    }

    protected S3UploadReusult uploadToS3In(String s3Path, File indleveringsfil) {
        RESTClient restClient = createRestClient(context.s3InUrl)
        def response = restClient.put(
                headers: ['Accept': JSON_API_HEADER],
                path: s3Path,
                requestContentType: ContentType.BINARY,
                body: indleveringsfil.bytes
        )

        printlnVerbose "Upload af '${indleveringsfil.name}' returnerede HTTP status ${response.statusLine}"
        assertResponseStatus(response, 200)
        return new S3UploadReusult(path: response.headers.location, md5: response.headers.'Content-MD5')
    }

    protected String aktiverMasseindlevering(String apiPath, String jsonBody) {
        String urlTilSvarfil
        String masseindleveringsresurse
        RESTClient restClient = createRestClient(context.baseUrl)
        restClient.post(
                headers: ['Accept': JSON_API_HEADER],
                path: apiPath,
                requestContentType: 'application/json',
                body: jsonBody
        ) { response, json ->
            printlnVerbose "Aktivering af masseindlevering returnerede HTTP status ${response.statusLine}"
            assertResponseStatus(response, 201)

            masseindleveringsresurse = response.headers.location
            String jsontxt = json.text
            printlnVerbose "Svar på aktivering: ${jsontxt}"
        }
        assert masseindleveringsresurse : 'Der skete en fejl ved aktiveringen'
        urlTilSvarfil = pollForFinalProcessingResult(masseindleveringsresurse)
        return urlTilSvarfil
    }

    protected String createRequestJsonBody(S3UploadReusult s3UploadReusult) {
        JsonBuilder requestJson = new JsonBuilder()
        requestJson.data {
            attributes {
                s3Key "${context.s3InUrl}${s3UploadReusult.path}"
                s3Md5Checksum s3UploadReusult.md5
            }
        }
        String jsonBody = requestJson.toString()
        return jsonBody
    }

    protected Closure assertStatus = { int status ->
        illegalStateIfNot response.status == expectedStatus, "Statuskoden var $response.status, men $expectedStatus var forventet"
    }

    protected static assertResponseStatus(def response, expectedStatus) {
        int status = response.status
        illegalStateIfNot status == expectedStatus, "Statuskoden var $status, men $expectedStatus var forventet"
    }

    RESTClient createRestClient(String baseUrl) {
        RESTClient client = new RESTClient(baseUrl)
        addCommonClientTraits(client)
        return client
    }

    HTTPBuilder createHttpClient(String baseUrl) {
        HTTPBuilder client = new HTTPBuilder(baseUrl)
        addCommonClientTraits(client)
        return client
    }

    void addCommonClientTraits(HTTPBuilder client) {
        addErrorHandlingForClient(client)
        addCertificateInfo(client)
        addDefaultHeaders(client)
    }

    void addCertificateInfo(HTTPBuilder client) {
        if (context.p12) {
            String password = findCertificatePassword()
            client.auth.certificate(new File(context.p12).toURI().toURL().toString(),
                    (password) as String)
        }
    }

    protected Object addDefaultHeaders(client) {
        context.extraHeaders.each { headerName, headerValue ->
            client.defaultRequestHeaders."${headerName}" = headerValue
        }
    }

    protected String pollForFinalProcessingResult(String location) {
        String urlTilSvarfil
        while (true) {
            String masseindleveringsstatus
            printlnVerbose "Henter status fra ${context.baseUrl}${location}"
            RESTClient client = createRestClient(context.baseUrl)
            client.get(uri: new URI( context.baseUrl+location ),
                    requestContentType: 'application/json'
            ) { response, json ->
                printlnVerbose "Masseindlevering returnerede HTTP status ${response.statusLine}"
                assertResponseStatus(response, 200)

                def slurper = new JsonSlurper().parseText(json.text)
                masseindleveringsstatus = slurper.data.attributes.status
                if (masseindleveringsstatus == 'FEJLET') {
                    println slurper
                }
                printlnVerbose "Status på masseindleveringen er: ${masseindleveringsstatus}"
                urlTilSvarfil = slurper.data?.links?.svarfil
            }
            if (!masseindleveringsstatus) {
                println("Der skete en fejl da status på aktiveringen skulle hentes fra serveren")
                System.exit(1)
            } else if (masseindleveringsstatus != 'IGANG') {
                break
            }
            sleep(3000)
            //do nothing, poll again
        }
        return urlTilSvarfil

    }

    protected String findCertificatePassword() {
        if (this.certificatePassword == null) {
            this.certificatePassword = context.certificatePassword ?: (System.console()?.readPassword("Enter certificate passphrase: ") as String) ?: ''
        }
        return this.certificatePassword
    }

    void printlnVerbose(String tekst) {
        if (context.verbose) println(tekst)
    }

    String generateS3Key(String period) {
        String s3Key
        if (context.s3Key) {
            s3Key = context.s3Key
        } else {
            String currentTimeIso = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
            s3Key = "${period}/$currentTimeIso"
        }
        printlnVerbose("Bruger følgende S3-nøgle ${s3Key}")
        return s3Key
    }

    File createZip(String inputDir) {
        String zipFileName = "${UUID.randomUUID().toString()}.zip"
        String outputDirPath = createOutputDir()
        String generatedZipPath = "$outputDirPath/$zipFileName"
        ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(generatedZipPath))
        findAllExceptHiddenAndDirectories(inputDir).each { file ->
            zipOutputStream.putNextEntry(new ZipEntry(file.name))
            file.withInputStream { InputStream inputStream ->
                Files.copy(inputStream, zipOutputStream)
            }
            zipOutputStream.closeEntry()
        }
        zipOutputStream.close()
        printlnVerbose "Har genereret zip med indleveringsfiler her: $generatedZipPath"
        File file = new File(generatedZipPath)
        file.deleteOnExit()
        return file
    }

    void unzip(File file, String outPath) {
        ZipFile zipFile = new ZipFile(file)
        new File(outPath).mkdirs()
        zipFile.entries().each { ZipEntry zipEntry ->
            File single = new File("${outPath}/${zipEntry.name}")
            single.write(zipFile.getInputStream(zipEntry).getText('UTF-8'))
        }
    }

    protected List<File> findAllExceptHiddenAndDirectories(String inputDir) {
        new File(inputDir).listFiles().findAll { it.isFile() }
    }

    protected String createOutputDir() {
        File tempDir = File.createTempDir()
        String outputDirPath = tempDir.absolutePath
        return outputDirPath
    }

    static void illegalStateIfNot(Object condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message)
        }
    }
}

class S3UploadReusult {
    String md5
    String path
}
