package cljdoc.render;

import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataHolder;

/**
 * Flexmark extension work around img ref link bug.
 * See: https://github.com/vsch/flexmark-java/issues/551
 */
public class FixupImgRefLinksExtension implements Parser.ParserExtension {
    private FixupImgRefLinksExtension() {
    }

    public static FixupImgRefLinksExtension create() {
        return new FixupImgRefLinksExtension();
    }

    @Override
    public void parserOptions(MutableDataHolder mutableDataHolder) {
    }

    @Override
    public void extend(Parser.Builder parserBuilder) {
        parserBuilder.postProcessorFactory(new FixupImgRefLinksPostProcessor.Factory());
    }

}
