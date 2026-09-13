import com.talentshift.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class TestScrapers {
    public static void main(String[] args) throws Exception {
        try {
            Document doc = Jsoup.connect("https://www.gulftalent.com/saudi-arabia/jobs")
                    .userAgent("Mozilla/5.0")
                    .timeout(10000).get();
            System.out.println("GulfTalent: " + doc.select("a.job-result-item").size());
        } catch (Exception e) { System.out.println("GulfTalent Error: " + e.getMessage()); }
        try {
            Document doc = Jsoup.connect("https://www.naukrigulf.com/jobs-in-saudi-arabia")
                    .userAgent("Mozilla/5.0")
                    .timeout(10000).get();
            System.out.println("Naukrigulf: " + doc.select(".srp-tuple").size());
        } catch (Exception e) { System.out.println("Naukrigulf Error: " + e.getMessage()); }
        try {
            Document doc = Jsoup.connect("https://careers.saudia.com/search/?q=&locationsearch=Saudi+Arabia")
                    .userAgent("Mozilla/5.0")
                    .timeout(10000).get();
            System.out.println("Saudia: " + doc.select("tr.data-row").size());
        } catch (Exception e) { System.out.println("Saudia Error: " + e.getMessage()); }
    }
}
