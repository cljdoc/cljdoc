package cljdoc.render;

import com.vladsch.flexmark.ast.*;
import com.vladsch.flexmark.parser.block.NodePostProcessor;
import com.vladsch.flexmark.parser.block.NodePostProcessorFactory;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeTracker;
import org.jetbrains.annotations.NotNull;

/**
 * Flexmark post processor to work around ref link bug.
 * See: https://github.com/vsch/flexmark-java/issues/551
 *
 * For every ImageRef node, we look for the pattern the bug produces
 * and manipulate AST to correct.
 *
 * `[<ImageRef>]<LinkRef>` is converted to `<LinkRef>` with `<ImageRef>`
 * as body.
 *
 * The pattern is unique enough that I expect it would be unlikely it affects
 * any other AST.
 */
public class FixupImgRefLinksPostProcessor extends NodePostProcessor {

    @Override
    public void process(@NotNull NodeTracker state, @NotNull Node node) {
        if (node instanceof ImageRef imageRef) {
            if ((imageRef.getPrevious() instanceof Text leftBr)
                && (leftBr.getChars().toString().equals("["))) {
                if ((imageRef.getNext() instanceof Text rightBr)
                    && (rightBr.getChars().toString().equals("]"))) {
                    if (rightBr.getNext() instanceof LinkRef linkRef) {
                        linkRef.removeChildren();
                        linkRef.appendChild(imageRef);
                        state.nodeAdded(imageRef);

                        rightBr.unlink();
                        leftBr.unlink();
                        state.nodeRemoved(rightBr);
                        state.nodeRemoved(leftBr);
                    }
                }
            }
        }
    }

    public static class Factory extends NodePostProcessorFactory {
        @SuppressWarnings("this-escape") // jdk21 has new linter, I *think* we are ok here?
        public Factory() {
            super(false);
            addNodes(ImageRef.class);
        }

        @NotNull
        @Override
        public NodePostProcessor apply(@NotNull Document document) {
            return new FixupImgRefLinksPostProcessor();
        }
    }
}
