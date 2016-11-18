import groovy.io.FileType

/**
 * Håndterer parsing af command line argumenter.
 */
class CliHelper {

    static {
        File.metaClass.getFileCount = {
            delegate.listFiles().count { true }
        }
    }

    public static final validCategories = ['udl\u00e5n', 'indl\u00e5n', 'prioritetsl\u00e5n', 'pantebreve']
    public static final Map validCategoryAlias = [
            'ud'       : 'udl\u00e5n',
            'ind'      : 'indl\u00e5n',
            'prioritet': 'prioritetsl\u00e5n',
            'pant'     : 'pantebreve']
    public static final String defaultBaseUrl = 'https://api.tfe.tse3pindberet.skat.dk'
    public static final String defaultS3InUrl = 'https://in.s3.tfe.tse3pindberet.skat.dk'
    public static final String defaultS3OutUrl = 'https://out.s3.tfe.tse3pindberet.skat.dk'
    public static final int defaultKontoIdLength = 30

    static Map parseOptions(args) {
        OptionAccessor options
        CliBuilder cli = new CliBuilder(usage: '\n\tindlever [options] <directory>\n\tindlever -m [options] <file>',
                header: 'Options:')
        cli.with {
            m longOpt: 'masseindlevering', 'Submit multiple reports at once in a zip file', required: false
            h longOpt: 'help', 'Usage information', required: false
            n longOpt: 'dry-run', "Dry run. Do not POST anything"
            b longOpt: 'base-url', args: 1, "Base url, e.g. $defaultBaseUrl"
            k longOpt: 'konto-id-length', args: 1, "Max length of KontoId. Default is $defaultKontoIdLength. Disabled if 0"
            o longOpt: 'output', args: 1, "(only masseindlevering) output file, e.g. out.zip"
            u longOpt: 's3-in-url', args: 1, "(only masseindlevering) S3 upload url, e.g. $defaultS3InUrl"
            d longOpt: 's3-out-url', args: 1, "(only masseindlevering) S3 download url, e.g. $defaultS3InUrl"
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
            throw new IllegalArgumentException("You must provide exactly one directory or file, not ${options.arguments().size}.")
        }

        if (!validCategories.contains(options.category) && !validCategoryAlias.containsKey(options.category)) {
            throw new IllegalArgumentException("Category cannot be '$options.category'.\n" +
                    "Must be one of '${(validCategories + validCategoryAlias.keySet()).join("', '")}'.")
        }

        if (!(options.period =~ /^[0-9]{4}(-0[369])?$/)) {
            throw new IllegalArgumentException("Period shall be either a four digit a year ('2017') or year followed by month (03, 06, or 09) . e.g. '2017-03'.")
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

        Map context = [:]
        context.dry = options.'dry-run'
        context.baseUrl = options.'base-url' ?: defaultBaseUrl
        context.s3InUrl = options.'s3-in-url' ?: defaultS3InUrl
        context.s3OutUrl = options.'s3-out-url' ?: defaultS3OutUrl
        context.kontoIdLength = options.'k' ?: defaultKontoIdLength
        context.category = validCategoryAlias.containsKey(options.category) ? validCategoryAlias[options.category] : options.category
        context.se = options.se
        context.period = options.period
        context.p12 = options.p12
        context.verbose = options.v
        context.masseindlevering = options.m
        if(context.masseindlevering){
            context.file = options.arguments()[0]
            context.output = options.output
        }
        else {
            context.directory = options.arguments()[0]
        }
        return context
    }


}
