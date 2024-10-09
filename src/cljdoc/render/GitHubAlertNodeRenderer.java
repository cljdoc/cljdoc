package cljdoc.render;

import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRendererFactory;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.data.DataHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

public class GitHubAlertNodeRenderer implements NodeRenderer {

    @Override
    public Set<NodeRenderingHandler<?>> getNodeRenderingHandlers() {
        Set<NodeRenderingHandler<?>> set = new HashSet<>();
        set.add(new NodeRenderingHandler<>(GitHubAlertBlock.class, this::render));
        return set;
    }

    // <div class="markdown-alert markdown-alert-tip">
    //   <p class="markdown-alert-title">tip</p>
    //   content
    // </div>
    private void render(GitHubAlertBlock node, NodeRendererContext context, HtmlWriter html) {
        String alertType = node.getAlertType().toString().toLowerCase();

        html.withAttr()
            .attr("class", "markdown-alert markdown-alert-" + alertType)
            .tag("div", false).line();

        html.withAttr()
            .attr("class", "markdown-alert-title")
            .tag("p", false)
            .text(alertType)
            .closeTag("p");

        context.renderChildren(node);

        html.closeTag("div").line();
    }

    public static class Factory implements NodeRendererFactory {
        @NotNull
        @Override
        public NodeRenderer apply(@NotNull DataHolder options) {
            return new GitHubAlertNodeRenderer();
        }
    }
}
