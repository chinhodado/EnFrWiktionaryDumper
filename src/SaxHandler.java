import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Hander for the SAX parser
 */
public class SaxHandler extends DefaultHandler {
    ArrayList<String> elementStack = new ArrayList<String>();
    String currentTitle = null;
    public int count = 0;
    static Connection connection;
    static PreparedStatement psParms;
    Statement stmt = null;

    public SaxHandler() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite::memory:");

            stmt = connection.createStatement();

            String sql = "DROP TABLE IF EXISTS Word;";
            stmt.executeUpdate(sql);

            stmt = connection.createStatement();
            sql = "CREATE TABLE Word " +
                    "( word              TEXT NOT NULL, " +
                    "  definition TEXT) ";
            stmt.executeUpdate(sql);

            psParms = connection.prepareStatement("INSERT INTO Word (word, definition) VALUES (?,?)");
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        if (count > 10) {
            try {
                stmt.executeUpdate("backup to dict.db");
                stmt.close();
            } catch (SQLException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            throw new SAXException("Stopped!");
        }

        if (qName.equals("page")) {
            currentTitle = null;
        }
        elementStack.add(qName);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        elementStack.remove(elementStack.size() - 1);
    }

    @Override
    public void characters(char ch[], int start, int length) throws SAXException {

        String value = new String(ch, start, length).trim();
        if(value.length() == 0) {
            return; // ignore white space
        }

        if("title".equals(currentElement())){
            currentTitle = value;
        }
        else if (currentElement().equals("text")) {
            if (value.contains("==French==")) {
                System.out.println(currentTitle);
                try {
                    count++;
                    String html = Jsoup.connect("http://en.wiktionary.org/w/index.php?title=" + currentTitle + "&printable=yes")
                            .ignoreContentType(true).execute().body();

                    // parse and do some initial cleaning of the DOM
                    Document doc = Jsoup.parse(html);
                    Element content = doc.select("#mw-content-text").first();
                    content.select("script").remove();               // remove <script> tags
                    content.select("noscript").remove();             // remove <noscript> tags
                    content.select("#toc").remove();                 // remove the table of content
                    WiktionaryDumper.removeComments(content);                         // remove comments
                    WiktionaryDumper.removeAttributes(content);                       // remove all attributes. Has to be at the end, otherwise can't grab id, etc.
                    WiktionaryDumper.removeEmptyTags(content);                        // remove all empty tags

                    // parse for the content of the French section, if it exist
                    Elements children = content.children();
                    boolean frenchFound = false;
                    boolean frenchEndReached = false;
                    boolean isCurrentlyBackSection = false;
                    Elements frenchCollection = new Elements();
                    for (Element elem : children) {
                        if (!frenchFound) {
                            if (elem.tagName().equals("h2") && elem.text().equals("French")) {
                                frenchFound = true;
                            }
                            else {
                                elem.remove();
                            }
                        }
                        else {
                            if (!elem.tagName().equals("h2")  &&                                          // French, English, etc.
                                !(elem.tagName().equals("h3") && elem.text().equals("External links")) && // remove external links section
                                !frenchEndReached) {
                                // get etylmology, pronunciation, etc. sections so that we can move them to the back
                                // of the page later, instead of having them at the beginning of the page
                                if (WiktionaryDumper.isBackSectionHeader(elem)) {
                                    WiktionaryDumper.backSectionsMap.put(elem.text(), true);
                                    isCurrentlyBackSection = true;
                                }
                                else if (WiktionaryDumper.isSubheaders(elem)) {
                                    // something other than etymology and pronunciation, etc.
                                    if (isCurrentlyBackSection) {
                                        isCurrentlyBackSection = false;
                                        // TODO: clear the map?
                                    }
                                    frenchCollection.add(elem);
                                }
                                else if (isCurrentlyBackSection) {
                                    // do nothing
                                }
                                else {
                                    isCurrentlyBackSection = false;
                                    frenchCollection.add(elem);
                                }
                            }
                            else {
                                frenchEndReached = true;
                                break;
                            }
                        }
                    }

                    // convert to text
                    String text = frenchCollection.toString();

                    // remove useless tags
                    text = text.replace("<span>", "").replace("</span>", "").replace("<a>", "").replace("</a>", "");

                    psParms.setString(1,  currentTitle);
                    psParms.setString(2,  text);
                    psParms.executeUpdate();
                } catch (Exception e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
    }

    private String currentElement() {
        return elementStack.get(elementStack.size() - 1);
    }

    private String currentElementParent() {
        if (elementStack.size() < 2) return null;
        return elementStack.get(elementStack.size() - 2);
    }
}