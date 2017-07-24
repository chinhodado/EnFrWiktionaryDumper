import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process raw db file
 * Created by Chin on 27-Jan-17.
 */
public class WiktionaryProcessor {
    // sections that we want to remove
    private static Set<String> removeSections;
    static
    {
        removeSections = new HashSet<>();
        removeSections.add("Etymology");
        removeSections.add("Etymology 1");
        removeSections.add("Etymology 2");
        removeSections.add("Etymology 3");
        removeSections.add("Pronunciation"); // TODO: need the pronunciation
//        removeSections.add("Conjugation"); // TODO: need the explanation
        removeSections.add("Anagrams");
        removeSections.add("External links");
        removeSections.add("References");
        removeSections.add("Descendants");
    }

    private static PreparedStatement psParms;
    private static final AtomicInteger doneCounter = new AtomicInteger();
    private static ConjugationProcessor conjugationProcessor;
    private static int totalWords;

    public static void main (String argv []) throws Exception {
        logLine("Program started.");

        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Connection connection_raw = DriverManager.getConnection("jdbc:sqlite:raw_en-fr_dict.db");

        Statement stmt = connection.createStatement();
        String sql = "CREATE TABLE Word " +
                "( name       TEXT NOT NULL, " +
                "  definition TEXT) ";
        stmt.executeUpdate(sql);
        stmt.executeUpdate("CREATE INDEX name_idx ON Word (name collate nocase)");

        psParms = connection.prepareStatement("INSERT INTO Word (name, definition) VALUES (?,?)");
        conjugationProcessor = new ConjugationProcessor(connection);

        Statement stmt_raw = connection_raw.createStatement();
        ResultSet rs = stmt_raw.executeQuery("SELECT COUNT(*) FROM Word");
        while (rs.next()){
            totalWords = rs.getInt(1);
        }

        Thread logThread = new Thread(() -> {
            while (!Thread.interrupted()) {
                System.out.print("\r");
                int done = doneCounter.get();
                double percentage = (double)done / totalWords * 100;
                System.out.print("Completed: " + done + "/" + totalWords + "(" + percentage + "%)                  ");

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        logThread.start();


        rs = stmt_raw.executeQuery("select * from word");
        while (rs.next()) {
            String word = rs.getString("name");
            String definition_raw = rs.getString("definition");
            processWord(word, definition_raw);
            doneCounter.incrementAndGet();
        }

        logThread.interrupt();
        logThread.join();

        System.out.println();
        System.out.println("Saving conjugation table");
        conjugationProcessor.saveToTable();

        // Dump the database contents to a file
        stmt.executeUpdate("backup to processed_dict.db");
        stmt.close();
        connection.close();
        logLine("Saved to processed_dict.db successfully. Everything done.");
    }

    private static void processWord(String word, String rawDefinition) {
        rawDefinition = rawDefinition.replaceAll("&nbsp;", " ");
        Element doc = Jsoup.parse(rawDefinition).body();

        Elements children = doc.children();
        boolean isCurrentlyRemoveSection = false;
        Elements frenchCollection = new Elements();

        for (int i = 0; i < children.size(); i++) {
            Element elem = children.get(i);

            if (isRemoveSectionHeader(elem)) {
                isCurrentlyRemoveSection = true;
            }
            else if (isSubheaders(elem) && !elem.text().equals("Conjugation")) {
                // a section that is not to be removed
                isCurrentlyRemoveSection = false;
                frenchCollection.add(elem);
            }
            else if (isCurrentlyRemoveSection || elem.text().startsWith("French Wikipedia has an article")) {
                // do nothing, skip
            }
            else if (isSubheaders(elem) && elem.text().equals("Conjugation")) {
                Element div = children.get(i+1);
//                if (!div.text().startsWith("Conjugation of")) {
//                    System.out.println("Warning: There is no 'Conjugation of' after Conjugation for " + word);
//                }
                Element table = div.getElementsByTag("table").first();

                if (table != null) {
                    conjugationProcessor.processConjugationFromTable(word, table);
                }

                // skip anything after this conjugation header and before the next header
                isCurrentlyRemoveSection = true;
            }
            else {
                isCurrentlyRemoveSection = false;

//                if (elem.tagName().equals("ol")) {
//                    boolean removedConjugation = false;
//                    for (Element li : elem.children()) {
//                        if (li.tagName().equals("li")) {
//                            if (conjugationProcessor.processConjugation(word, li.text())) {
//                                li.remove();
//                                removedConjugation = true;
//                            }
//                        }
//                    }
//
//                    if (removedConjugation) {
//                        elem.appendText("$conjugation$");
//                    }
//                }

                frenchCollection.add(elem);
            }
        }

        try {
            psParms.setString(1, word);
            psParms.setString(2, frenchCollection.toString());
            psParms.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static boolean isSubheaders(Element elem) {
        return elem.tagName().equals("h3") || elem.tagName().equals("h4") || elem.tagName().equals("h5");
    }

    private static boolean isRemoveSectionHeader(Element elem) {
        return isSubheaders(elem) && removeSections.contains(elem.text());
    }

    private static void logLine(String txt) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
        System.out.println(dateFormat.format(date) + ": " + txt);
    }
}
