import groovy.io.FileType
import groovy.cli.commons.CliBuilder
import groovy.cli.commons.OptionAccessor

/**
 * Håndterer parsing af command line argumenter.
 */
class CliHelper {

    public static final List<String> validCategories = ['udl\u00e5n', 'indl\u00e5n', 'prioritetsl\u00e5n', 'pantebreve', 'pensiondiverse']
    public static final Map<String, String> validCategoryAlias = [
            'ud'       : 'udl\u00e5n',
            'ind'      : 'indl\u00e5n',
            'prioritet': 'prioritetsl\u00e5n',
            'pant'     : 'pantebreve',
            'pension'  : 'pensiondiverse']
    public static final String defaultBaseUrl = 'https://api.tse3pindberet.tfe.skat.dk'

    static Map parseOptions(args) {

        OptionAccessor options
        CliBuilder cli = new CliBuilder(usage: 'indlever [options] <directory>', header: 'Options:')
        cli.with {
            m longOpt: 'masseindlevering', 'Submit a large amount of reports at once', required: false
            h longOpt: 'help', 'Usage information', required: false
            n longOpt: 'dry-run', "Dry run. Do not POST anything"
            b longOpt: 'base-url', args: 1, "Base url, e.g. $defaultBaseUrl"
            o longOpt: 'outdir', args: 1, "output directory, e.g. ~/out"
            c longOpt: 'category', args: 1, "Reporting category. e.g. one of '${validCategories.join("', '")}' or '${validCategoryAlias.keySet().join("', '")}'", required: true
            s longOpt: 'se', args: 1, 'SE number of the reporter', required: true
            p longOpt: 'period', args: 1, 'Period, e.g. "2017"', required: true
            v longOpt: 'verbose', 'Verbose error messages'
            d longOpt: 'datafile-key', args: 1, 'The key used when storing masseindlevering datafile on in.s3 host.'
            H(longOpt: 'header', args: 2, valueSeparator: '=', argName: 'property=value', 'HTTP Header eg. "content-type=application/pdf", this argument can be repeated')
            _ longOpt: 'p12', args: 1, 'PKCS12 Key otherFile, .e.g. "~/.oces/indberetter.p12"'
        }
        options = cli.parse(args)
        if (!options) {
            throw new IllegalArgumentException()
        }
        if (options.h) {
            cli.usage()
            throw new IllegalArgumentException()
        }
        if (options.arguments().size() != 1) {
            cli.usage()
            throw new IllegalArgumentException("You must provide exactly one directory or otherFile, not ${options.arguments().size()}.")
        }
        if (options.outdir) {
            if (new File(options.outdir).exists()) {
                throw new IllegalArgumentException("The folder ${options.outdir} already exists, please chosse an other output folder")
            }
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

            File ocesFolder = new File("${System.getProperty('user.home')}/.oces")
            if (ocesFolder.exists()) {
                ocesFolder.eachFile(FileType.FILES) {
                    if (it.name.endsWith('.p12')) {
                        pkcs12List << it.absolutePath
                    }
                }
            }
            println "NOTICE: You have not provided a PKCS12 otherFile."
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
        context.category = validCategoryAlias.containsKey(options.category) ? validCategoryAlias[options.category] : options.category
        context.se = options.se
        context.period = options.period
        context.p12 = options.p12
        context.verbose = options.v
        context.masseindlevering = options.m
        context.certificatePassword = null
        if (context.masseindlevering) {
            context.datafileKey = options.'datafile-key' ?: null
            context.s3InUrl = replaceHost(context, 'in.s3')
            context.s3OutUrl = replaceHost(context, 'out.s3')
            context.outdir = findOutdir(options)
        }
        context.directory = options.arguments()[0]
        if (options.header) {
            context.extraHeaders = options.headers.toSpreadMap()
        }
        return context
    }

    protected static String findOutdir(OptionAccessor options) {
        return options.outdir ?: "${options.arguments()[0]}-svar-${new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getDefault())}"
    }

    protected static String replaceHost(Map context, String replacement) {
        return context.baseUrl.replaceAll('^(https?://)api', "\$1$replacement")
    }

}
