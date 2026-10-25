import io.github.ivangsa.gitpublisher.maven.it.ItSupport
ItSupport.assertOnePublicationOf(basedir, 'readme-workflow')
def workflow = new File(basedir, '.github/workflows/publish.yml').text
if (!workflow.contains('concurrency:') || !workflow.contains('needs: verify')) {
    throw new IllegalStateException('publish.yml lost its concurrency group or its separate publish stage')
}
return true
