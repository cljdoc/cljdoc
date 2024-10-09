package cljdoc.render;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataHolder;
import org.jetbrains.annotations.NotNull;

public class GitHubAlertExtension implements Parser.ParserExtension,
                                             HtmlRenderer.HtmlRendererExtension {

    private GitHubAlertExtension() {
    }

    public static GitHubAlertExtension create() {
        return new GitHubAlertExtension();
    }

    @Override
    public void rendererOptions(@NotNull MutableDataHolder options) {
        // we have no options
    }

    @Override
    public void parserOptions(MutableDataHolder options) {
        // we have no options
    }

    @Override
    public void extend(Parser.Builder parserBuilder) {
        parserBuilder.postProcessorFactory(new GitHubAlertPostProcessor.Factory());
    }

    @Override
    public void extend(@NotNull HtmlRenderer.Builder htmlRendererBuilder, @NotNull String rendererType) {
        if (htmlRendererBuilder.isRendererType("HTML")) {
            htmlRendererBuilder.nodeRendererFactory(new GitHubAlertNodeRenderer.Factory());
        }
    }
}
