import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;


public class WiktionaryDumper {
    // sections that we want to move to the back of the page
    static HashMap<String, Boolean> backSectionsMap;
    static
    {
        backSectionsMap = new HashMap<String, Boolean>();
        backSectionsMap.put("Etymology", false);
        backSectionsMap.put("Etymology 1", false);
        backSectionsMap.put("Etymology 2", false);
        backSectionsMap.put("Pronunciation", false);
    }

    public static void main (String argv []) {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        try {
            InputStream xmlInput  = new FileInputStream("C:\\Users\\Chin\\Downloads\\enwiktionary-20150602-pages-articles.xml");

            SAXParser saxParser = factory.newSAXParser();
            SaxHandler handler = new SaxHandler();
            saxParser.parse(xmlInput, handler);
        } catch (Throwable err) {
            err.printStackTrace ();
        }
    }

    public static boolean isSubheaders(Element elem) {
        return elem.tagName().equals("h3") || elem.tagName().equals("h4") || elem.tagName().equals("h5");
    }

    public static boolean isBackSectionHeader(Element elem) {
        return isSubheaders(elem) && backSectionsMap.containsKey(elem.text());
    }

    public static void removeComments(Node node) {
        for (int i = 0; i < node.childNodes().size();) {
            Node child = node.childNode(i);
            if (child.nodeName().equals("#comment"))
                child.remove();
            else {
                removeComments(child);
                i++;
            }
        }
    }

    public static void removeAttributes(Element doc) {
        Elements el = doc.getAllElements();
        for (Element e : el) {
            Attributes at = e.attributes();
            for (Attribute a : at) {
                e.removeAttr(a.getKey());
            }
        }
    }

    public static void removeEmptyTags(Element doc) {
        for (Element element : doc.select("*")) {
            if (!element.hasText() && element.isBlock()) {
                element.remove();
            }
        }
    }
}
