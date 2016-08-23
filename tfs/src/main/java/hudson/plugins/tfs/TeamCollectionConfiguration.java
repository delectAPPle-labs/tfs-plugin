package hudson.plugins.tfs;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.HostnameRequirement;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.plugins.tfs.util.StringHelper;
import hudson.plugins.tfs.util.UriHelper;
import hudson.plugins.tfs.util.TeamRestClient;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.List;

public class TeamCollectionConfiguration extends AbstractDescribableImpl<TeamCollectionConfiguration> {

    private final String collectionUrl;
    private final String credentialsId;

    @DataBoundConstructor
    public TeamCollectionConfiguration(final String collectionUrl, final String credentialsId) {
        this.collectionUrl = collectionUrl;
        this.credentialsId = credentialsId;
    }

    public String getCollectionUrl() {
        return collectionUrl;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<TeamCollectionConfiguration> {

        @Override
        public String getDisplayName() {
            return "Team Project Collection";
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckCollectionUrl(
                @QueryParameter final String value) {

            try {
                new URL(value);
            }
            catch (MalformedURLException e) {
                return FormValidation.error("Malformed TFS/Team Services collection URL (%s)", e.getMessage());
            }

            // TODO: check that it's not a deep URL to a repository, work item, API endpoint, etc.

            return FormValidation.ok();
        }

        @SuppressWarnings("unused")
        public FormValidation doTestCredentials(
                @QueryParameter final String collectionUrl,
                @QueryParameter final String credentialsId) {

            final String errorTemplate = "Error: %s";

            String hostName = null;
            try {
                final URL url = new URL(collectionUrl);
                hostName = url.getHost();
            }
            catch (final MalformedURLException e) {
                return FormValidation.error(errorTemplate, e.getMessage());
            }

            try {
                final StandardUsernamePasswordCredentials credential = findCredential(hostName, credentialsId);
                if (StringHelper.endsWithIgnoreCase(hostName, ".visualstudio.com")) {
                    if (credential == null) {
                        return FormValidation.error(errorTemplate, "Team Services accounts need credentials, preferably a Personal Access Token");
                    }
                }
                final URI collectionUri = URI.create(collectionUrl);
                testConnection(collectionUri, credential);
            }
            catch (final IOException e) {
                return FormValidation.error(e, errorTemplate, e.getMessage());
            }

            return FormValidation.ok("Success!");
        }

        @SuppressWarnings("unused")
        public ListBoxModel doFillCredentialsIdItems(
            @QueryParameter final String collectionUrl) {

            final Jenkins jenkins = Jenkins.getInstance();

            String hostName = null;
            try {
                final URL url = new URL(collectionUrl);
                hostName = url.getHost();
            }
            catch (final MalformedURLException ignored) {
            }

            if (hostName == null || !jenkins.hasPermission(Jenkins.ADMINISTER)) {
                return new ListBoxModel();
            }

            final List<StandardUsernamePasswordCredentials> matches = findCredentials(hostName);
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withAll(matches);
        }
    }

    static void testConnection(final URI collectionUri, final StandardUsernamePasswordCredentials credentials) throws IOException {

        final TeamRestClient client = new TeamRestClient(collectionUri, credentials);
        client.ping();
    }

    static StandardUsernamePasswordCredentials findCredential(final String hostName, final String credentialsId) {
        final List<StandardUsernamePasswordCredentials> matches = findCredentials(hostName);
        final CredentialsMatcher matcher = CredentialsMatchers.withId(credentialsId);
        final StandardUsernamePasswordCredentials result = CredentialsMatchers.firstOrNull(matches, matcher);
        return result;
    }

    static List<StandardUsernamePasswordCredentials> findCredentials(final String hostName) {
        final Jenkins jenkins = Jenkins.getInstance();
        final HostnameRequirement requirement = new HostnameRequirement(hostName);
        final List<StandardUsernamePasswordCredentials> matches =
                CredentialsProvider.lookupCredentials(
                        StandardUsernamePasswordCredentials.class,
                        jenkins,
                        ACL.SYSTEM,
                        requirement
                );
        return matches;
    }

    static StandardUsernamePasswordCredentials findCredentialsById(final String credentialsId) {
        final Jenkins jenkins = Jenkins.getInstance();
        final List<StandardUsernamePasswordCredentials> matches =
                CredentialsProvider.lookupCredentials(
                        StandardUsernamePasswordCredentials.class,
                        jenkins,
                        ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList()
                );
        final CredentialsMatcher matcher = CredentialsMatchers.withId(credentialsId);
        final StandardUsernamePasswordCredentials result = CredentialsMatchers.firstOrNull(matches, matcher);
        return result;
    }

    // TODO: we'll probably also want findCredentialsForGitRepo, where we match part of the URL path
    public static StandardUsernamePasswordCredentials findCredentialsForCollection(final URI collectionUri) {
        final TeamPluginGlobalConfig config = TeamPluginGlobalConfig.get();
        // TODO: consider using a different data structure to speed up this look-up
        final List<TeamCollectionConfiguration> pairs = config.getCollectionConfigurations();
        for (final TeamCollectionConfiguration pair : pairs) {
            final String candidateCollectionUrlString = pair.getCollectionUrl();
            final URI candidateCollectionUri = URI.create(candidateCollectionUrlString);
            if (UriHelper.areSame(candidateCollectionUri, collectionUri)) {
                final String credentialsId = pair.credentialsId;
                if (credentialsId != null) {
                    return findCredentialsById(credentialsId);
                }
                return null;
            }
        }
        final String template = "There is no team project collection configured for the URL '%1$s'.\n" +
                "Please go to Jenkins > Manage Jenkins > Configure System and then " +
                "add a Team Project Collection with a Collection URL of '%1$s'.";
        final String message = String.format(template, collectionUri);
        throw new IllegalArgumentException(message);
    }
}
