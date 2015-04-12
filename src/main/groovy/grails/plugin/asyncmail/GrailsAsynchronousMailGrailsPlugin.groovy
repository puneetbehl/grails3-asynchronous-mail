package grails.plugin.asyncmail

import grails.plugins.Plugin
import grails.plugins.mail.MailService
import grails.plugins.quartz.JobDescriptor
import grails.plugins.quartz.JobManagerService
import grails.plugins.quartz.TriggerDescriptor
import groovy.util.logging.Commons
import org.quartz.Scheduler
import org.quartz.TriggerKey
import org.springframework.context.ApplicationContext

@Commons
class GrailsAsynchronousMailGrailsPlugin extends Plugin {

    // the version or versions of Grails the plugin is designed for
    def grailsVersion = "3.0.0 > *"
    def loadAfter = ['mail', 'quartz', 'hibernate', 'hibernate4', 'mongodb']
    // resources that are excluded from plugin packaging
    def pluginExcludes = [
        "grails-app/views/error.gsp",
        "grails-app/views/test/**",
        "src/webapp/WEB-INF/**",
        "grails-app/assets/images/**",
        "grails-app/assets/javascripts/**",
        "grails-app/i18n"
    ]

    // TODO Fill in these fields
    def title = "Grails Asynchronous Mail Plugin" // Headline display name of the plugin
    def author = "Puneet Behl"
    def authorEmail = "puneet.behl007@gmail.com"
    def description = '''\
    The plugin realises asynchronous mail sending. It stores messages in the DB and sends them asynchronously by the quartz job.
'''
    def profiles = ['web']

    // URL to the plugin's documentation
     def documentation = "http://www.grails.org/plugin/asynchronous-mail"

    // Extra (optional) plugin metadata

    // License: one of 'APACHE', 'GPL2', 'GPL3'
    def license = "APACHE"

    // Details of company behind the plugin (if there is one)
    def organization = [ name: "Intelligrape Softwares", url: "http://www.intelligrape.com/" ]

    // Any additional developers beyond the author specified above.
    def developers = [ [ name: "Puneet Behl", email: "puneet.behl007@gmail.com" ]]

    // Location of the plugin's issue tracker.
    def issueManagement = [system: 'JIRA', url: 'http://jira.grails.org/browse/GPASYNCHRONOUSMAIL']

    // Online location of the plugin's browseable source code.
//    def scm = [ url: "http://svn.codehaus.org/grails-plugins/" ]

    Closure doWithSpring() { {->
            // TODO Implement runtime spring config (optional)
            loadAsyncMailConfig(config)

            // The mail service from Mail plugin
            nonAsynchronousMailService(MailService) {
                mailMessageBuilderFactory = ref("mailMessageBuilderFactory")
                grailsApplication = grailsApplication
            }

            asynchronousMailMessageBuilderFactory(AsynchronousMailMessageBuilderFactory) {
                it.autowire = true
            }
        } 
    }

    void doWithDynamicMethods() {
        // TODO Implement registering dynamic methods to classes (optional)
    }

    void doWithApplicationContext() {
        // TODO Implement post initialization spring config (optional)
        // Register alias for the asynchronousMailService
        applicationContext.registerAlias('asynchronousMailService', 'asyncMailService')

        // Configure sendMail methods
        configureSendMail(grailsApplication, applicationContext)

        // Starts jobs
        startJobs(grailsApplication, applicationContext)
    }

    void onChange(Map<String, Object> event) {
        // TODO Implement code that is executed when any artefact that this plugin is
        // watching is modified and reloaded. The event contains: event.source,
        // event.application, event.manager, event.ctx, and event.plugin.
        // Configure sendMail methods
        configureSendMail(grailsApplication, (ApplicationContext) event.ctx)
    }

    void onConfigChange(Map<String, Object> event) {
        // TODO Implement code that is executed when the project configuration changes.
        // The event is the same as for 'onChange'.
    }

    void onShutdown(Map<String, Object> event) {
        // TODO Implement code that is executed when the application shuts down (optional)
    }

    /**
     * Start the send job and the messages collector.
     *
     * If the plugin is used in cluster we have to remove old triggers.
     */
    def startJobs(config, applicationContext) {
        def asyncMailConfig = config.asynchronous.mail
        if (!asyncMailConfig.disable) {
            JobManagerService jobManagerService = applicationContext.jobManagerService
            Scheduler quartzScheduler = applicationContext.quartzScheduler

            // Get our jobs
            List<JobDescriptor> jobDescriptors = jobManagerService.getJobs("AsynchronousMail")

            // Remove old triggers for the send job
            log.debug("Removing old triggers for the AsynchronousMailJob")
            JobDescriptor sjd = jobDescriptors.find { it.name == 'grails.plugin.asyncmail.AsynchronousMailJob' }
            sjd?.triggerDescriptors?.each {TriggerDescriptor td ->
                def triggerKey = new TriggerKey(td.name, td.group)
                quartzScheduler.unscheduleJob(triggerKey)
                log.debug("Removed the trigger ${triggerKey} for the AsynchronousMailJob")
            }

            // Schedule the send job
            def sendInterval = (Long) asyncMailConfig.send.repeat.interval
            log.debug("Scheduling the AsynchronousMailJob with repeat interval ${sendInterval}ms")
            AsynchronousMailJob.schedule(sendInterval)
            log.debug("Scheduled the AsynchronousMailJob with repeat interval ${sendInterval}ms")

            // Remove old triggers for the collector job
            log.debug("Removing old triggers for the ExpiredMessagesCollectorJob")
            JobDescriptor cjd = jobDescriptors.find { it.name == 'grails.plugin.asyncmail.ExpiredMessagesCollectorJob' }
            cjd?.triggerDescriptors?.each {TriggerDescriptor td ->
                def triggerKey = new TriggerKey(td.name, td.group)
                quartzScheduler.unscheduleJob(triggerKey)
                log.debug("Removed the trigger ${triggerKey} for the ExpiredMessagesCollectorJob")
            }

            // Schedule the collector job
            def collectInterval = (Long) asyncMailConfig.expired.collector.repeat.interval
            log.debug("Scheduling the ExpiredMessagesCollectorJob with repeat interval ${collectInterval}ms")
            ExpiredMessagesCollectorJob.schedule(collectInterval)
            log.debug("Scheduled the ExpiredMessagesCollectorJob with repeat interval ${collectInterval}ms")
        }
    }

    /**
     * Configure sendMail methods
     */
    static configureSendMail(application, ApplicationContext applicationContext){
        def asyncMailConfig = application.config.asynchronous.mail

        // Override the mailService
        if (asyncMailConfig.override) {
            applicationContext.mailService.metaClass*.sendMail = { Closure callable ->
                applicationContext.asynchronousMailService?.sendAsynchronousMail(callable)
            }
        } else {
            applicationContext.asynchronousMailService.metaClass*.sendMail = { Closure callable ->
                applicationContext.asynchronousMailService?.sendAsynchronousMail(callable)
            }
        }
    }

    /**
     * Loads the asynchronous mail configuration.
     *
     * 1. Loads the grails configuration.
     * 2. Merges it with the default asynchronous mail configuration.
     * 3. Merges it with the user asynchronous mail configuration.
     *
     * http://swestfall.blogspot.co.uk/2011/08/grails-plugins-and-default-configs.html
     */
    private void loadAsyncMailConfig(config) {
        /*GroovyClassLoader classLoader = new GroovyClassLoader(getClass().classLoader)
        // merging default config into main application config
        ConfigObject currentAsyncConfig = config.asynchronous.mail
        ConfigObject defaultAsyncConfig = new ConfigSlurper(Environment.current.name)
                .parse(classLoader.loadClass('DefaultAsynchronousMailConfig'))

        ConfigObject newAsyncConfig = new ConfigObject()
        newAsyncConfig.putAll( defaultAsyncConfig.asynchronous.mail.merge(currentAsyncConfig))

        config.asynchronous.mail = newAsyncConfig

        // merging user-defined config into main 4application config if provided
        try {
            config.merge(new ConfigSlurper(Environment.current.name).parse(
                    classLoader.loadClass('AsynchronousMailConfig'))
            )
        } catch (Exception ignored) {
            // ignore, just use the defaults
        }*/
    }
}
