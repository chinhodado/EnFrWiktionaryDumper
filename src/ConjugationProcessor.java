import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Process conjugation
 * Created by Chin on 29-Jan-17.
 */
public class ConjugationProcessor {
    private static class Conjugation {
        String[] presentIndicatif = new String[6];
        String[] imparfaitIndicatif = new String[6];
        String[] passeSimple = new String[6];
        String[] futurSimple = new String[6];
        String[] presentSubjunctif = new String[6];
        String[] imparfaitSubjunctif = new String[6];
        String[] conditionel = new String[6];
        String[] imperatif = new String[6]; // only need 3
    }

    private final HashMap<String, Conjugation> conjugationMap = new HashMap<>();
    private Pattern tenseMoodMatcher;
    private PreparedStatement psInsertConj;

    public ConjugationProcessor(Connection connection) throws SQLException {
        Statement stmt = connection.createStatement();
        String sql = "CREATE TABLE Conjugation (" +
                "name TEXT NOT NULL, " +
                "presentIndicatif TEXT NOT NULL, " +
                "imparfaitIndicatif TEXT NOT NULL, " +
                "passeSimple TEXT NOT NULL, " +
                "futurSimple TEXT NOT NULL, " +
                "presentSubjonctif TEXT NOT NULL, " +
                "imparfaitSubjunctif TEXT NOT NULL, " +
                "conditionel TEXT NOT NULL, " +
                "imperatif TEXT NOT NULL)";
        stmt.executeUpdate(sql);

        psInsertConj = connection.prepareStatement("INSERT INTO Conjugation (name, presentIndicatif," +
                "imparfaitIndicatif, passeSimple, futurSimple, presentSubjonctif, imparfaitSubjunctif, conditionel, imperatif)" +
                " VALUES (?,?,?,?,?,?,?,?,?)");

        String[] tenseMood = new String[] {"present indicative", "imperfect indicative",
                "past historic", "simple future", "present subjunctive", "imperfect subjunctive",
                "conditional", "imperative"};
        String regex = "([f|F]irst|[s|S]econd|[t|T]hird)-person (singular|plural) (present indicative|imperfect indicative|" +
                "past historic|simple future|future|future indicative|indicative future|present subjunctive|imperfect subjunctive|" +
                "conditional|imperative|present imperative) of ([a-zA-Z0-9àâäèéêëîïôœùûüÿçÀÂÄÈÉÊËÎÏÔŒÙÛÜŸÇ]+)";
        tenseMoodMatcher = Pattern.compile(regex);
    }

    public boolean processConjugation(String word, String sentence) {
        Matcher matcher = tenseMoodMatcher.matcher(sentence);
        if (matcher.find()) {
            String person = matcher.group(1);
            String number = matcher.group(2);
            String tenseMood = matcher.group(3);
            String infinitif = matcher.group(4);

            if (!conjugationMap.containsKey(infinitif)) {
                conjugationMap.put(infinitif, new Conjugation());
            }

            Conjugation conj = conjugationMap.get(infinitif);
            String[] tenseMoodArr;
            switch (tenseMood) {
                case "present indicative":
                    tenseMoodArr = conj.presentIndicatif;
                    break;
                case "imperfect indicative":
                    tenseMoodArr = conj.imparfaitIndicatif;
                    break;
                case "past historic":
                    tenseMoodArr = conj.passeSimple;
                    break;
                case "simple future":
                case "future":
                case "future indicative":
                case "indicative future":
                    tenseMoodArr = conj.futurSimple;
                    break;
                case "present subjunctive":
                    tenseMoodArr = conj.presentSubjunctif;
                    break;
                case "imperfect subjunctive":
                    tenseMoodArr = conj.imparfaitSubjunctif;
                    break;
                case "conditional":
                    tenseMoodArr = conj.conditionel;
                    break;
                case "imperative":
                case "present imperative":
                    tenseMoodArr = conj.imperatif;
                    break;
                default:
                    throw new Error("Unknown tense/mood: " + tenseMood + " for word: " + word);
            }

            int idx;
            person = person.toLowerCase();
            switch(person) {
                case "first":
                    switch (number) {
                        case "singular":
                            idx = 0;
                            break;
                        case "plural":
                            idx = 3;
                            break;
                        default:
                            throw new Error("Unknown number: " + number + " for word: " + word);
                    }
                    break;
                case "second":
                    switch (number) {
                        case "singular":
                            idx = 1;
                            break;
                        case "plural":
                            idx = 4;
                            break;
                        default:
                            throw new Error("Unknown number: " + number + " for word: " + word);
                    }
                    break;
                case "third":
                    switch (number) {
                        case "singular":
                            idx = 2;
                            break;
                        case "plural":
                            idx = 5;
                            break;
                        default:
                            throw new Error("Unknown number: " + number + " for word: " + word);
                    }
                    break;
                default:
                    throw new Error("Unknown person: " + person + " for word: " + word);
            }

            if (tenseMoodArr[idx] == null) {
                tenseMoodArr[idx] = word;
            }
            else {
                tenseMoodArr[idx] = tenseMoodArr[idx] + "/" + word;
            }

            return true;
        }

        return false;
    }

    /**
     * Use either this or processConjugation(), not both
     * @param table The conjugation table
     */
    public void processConjugationFromTable(String word, Element table) {
        if (conjugationMap.containsKey(word)) {
            System.out.println();
            System.out.println("Warning: Conjugation already exists for word: " + word);
        }

        Conjugation conj = new Conjugation();
        Element tbody = table.getElementsByTag("tbody").first();
        Elements rows = tbody.getElementsByTag("tr");

        // row index for present indicative, imperfect indicative, past historic, future, conditional,
        // present subjunctive, imperfect subjective, imperative
        int[] rowIdx = new int[] {8,9,10,11,12,19,20,24};
        String[][] conjArr = new String[][] {conj.presentIndicatif, conj.imparfaitIndicatif, conj.passeSimple,
            conj.futurSimple, conj.conditionel, conj.presentSubjunctif, conj.imparfaitSubjunctif, conj.imperatif};

        for (int i = 0; i < rowIdx.length; i++) {
            Element row = rows.get(rowIdx[i]);
            String[] arr = conjArr[i];
            Elements tds = row.getElementsByTag("td");

            for (int j = 0; j < 6; j++) {
                Element td = tds.get(j);
                arr[j] = td.textNodes().get(0).text();
            }
        }

        conjugationMap.put(word, conj);
    }

    public void saveToTable() throws SQLException {
        for (Map.Entry<String, Conjugation> entry : conjugationMap.entrySet()) {
            String word = entry.getKey();
            Conjugation conj = entry.getValue();
            psInsertConj.setString(1, word);
            psInsertConj.setString(2, String.join("|", conj.presentIndicatif));
            psInsertConj.setString(3, String.join("|", conj.imparfaitIndicatif));
            psInsertConj.setString(4, String.join("|", conj.passeSimple));
            psInsertConj.setString(5, String.join("|", conj.futurSimple));
            psInsertConj.setString(6, String.join("|", conj.presentSubjunctif));
            psInsertConj.setString(7, String.join("|", conj.imparfaitSubjunctif));
            psInsertConj.setString(8, String.join("|", conj.conditionel));
            psInsertConj.setString(9, String.join("|", conj.imperatif));
            psInsertConj.executeUpdate();
        }
    }
}
