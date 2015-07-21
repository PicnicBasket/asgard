import com.netflix.asgard.userdata.DefaultUserDataProvider
import com.netflix.asgard.userdata.UserDataPropertyKeys
import com.netflix.asgard.UserContext
import com.netflix.asgard.plugin.AdvancedUserDataProvider
import com.netflix.asgard.plugin.UserDataProvider
import com.netflix.asgard.userdata.PropertiesUserDataProvider
import com.netflix.asgard.model.LaunchContext
import javax.xml.bind.DatatypeConverter
import com.amazonaws.services.ec2.model.Image
import org.springframework.beans.factory.annotation.Autowired
import com.netflix.asgard.ApplicationService
import com.netflix.asgard.ConfigService
import com.netflix.asgard.PluginService
import com.netflix.asgard.Relationships
import com.netflix.frigga.NameValidation
import com.netflix.frigga.Names

/**
 * Builds user data Dust style.
 */
class DustAdvancedUserDataProvider implements AdvancedUserDataProvider {

    @Autowired
    ApplicationService applicationService

    @Autowired
    ConfigService configService

    @Autowired
    PluginService pluginService

    @Override
    String buildUserData(LaunchContext launchContext) {
        UserContext userContext = launchContext.userContext
        String appNameFromApplication = launchContext.application?.name
        String groupName = launchContext.autoScalingGroup?.autoScalingGroupName ?: ''
        String launchConfigName = launchContext.launchConfiguration?.launchConfigurationName ?: ''
        Image image = launchContext.image
        String appName = appNameFromApplication ?: Relationships.appNameFromGroupName(groupName) ?:
            Relationships.packageFromAppVersion(image.appVersion) ?: ''

        // We need to get the incremental push number and mod 2 it to get blue or green
        // first ASG does not have a push number, second starts push number at 000
        String pushName = Relationships.dissectCompoundName(groupName).push ?: "v-1"
        String devPhaseName = Relationships.dissectCompoundName(groupName).devPhase ?: "none"

        int pushvalue = pushName.tokenize('v')[0].toInteger()
        if (pushvalue == null) {
                pushvalue = -1
        }

        String blueGreen = (pushvalue % 2 == 0) ? "GREEN" : "BLUE"
        if  (devPhaseName != "prod") {
          blueGreen = devPhaseName.toUpperCase();
        }

        PropertiesUserDataProvider propertiesUserDataProvider = new PropertiesUserDataProvider(
                configService: configService, applicationService: applicationService)
        Map<String, String> props = propertiesUserDataProvider.mapProperties(userContext, appName, groupName,
                launchConfigName)

        // Add company specific stuff
        String initialisePowershell = "<powershell>\n"
                initialisePowershell += "#Group Name: " + groupName + "\n"
                initialisePowershell += "#DevPhase: " + devPhaseName + "\n"
                initialisePowershell += "#Push: " + blueGreen + "\n"
                initialisePowershell += "\$Env:SERVER_GROUP_IDENTITY=\""+blueGreen+"\"\n"
                initialisePowershell += "[Environment]::SetEnvironmentVariable(\"SERVER_GROUP_IDENTITY\", \""+blueGreen+"\", \"Machine\")\n"
                initialisePowershell += "Invoke-Expression c:\\chef\\cache\\tentacle.ps1\n"
                initialisePowershell += "</powershell>\n"
        String concatenated = initialisePowershell
        DatatypeConverter.printBase64Binary(concatenated.bytes)
    }
}
