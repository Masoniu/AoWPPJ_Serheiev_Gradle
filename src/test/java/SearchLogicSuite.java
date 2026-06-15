import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses(MatcherEngineTest.class)
@IncludeTags({"search", "core"})
public class SearchLogicSuite {
}