import io.github.ivangsa.gitpublisher.maven.it.ItSupport
ItSupport.assertNothingPushed(basedir)
def log = ItSupport.log(basedir)
assert log.contains('differ from those of') && log.contains(': repository. Every participant')
assert !log.contains('Checked out branch') && !log.contains('does not exist yet')
return true
