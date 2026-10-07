import org.apache.commons.text.StringSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static java.lang.System.exit;

public class NRTRDEReportSender {
    private final Logger logger = LoggerFactory.getLogger(NRTRDEReportSender.class);

    Properties properties = new Properties();
    DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");
    DateTimeFormatter yyyyMMdd = DateTimeFormatter.ofPattern("yyyyMMdd");
    DateTimeFormatter yyMMdd = DateTimeFormatter.ofPattern("yyMMdd");

    public NRTRDEReportSender() throws IOException {
        try (InputStream input = Files.newInputStream(Paths.get("./application.properties"))) {
            properties.load(input);
        }
    }


    public void main(String[] args) throws Exception {
        NRTRDEReportSender reportSender = new NRTRDEReportSender();
        LocalDateTime now = LocalDateTime.now();
        switch (args.length) {
            case 0:
                System.out.println("Usages <report_type> <daytime> :: report_type: 'hourly'/'daily', daytime:'yyMMddHHmm' format");
                exit(1);
                break;
            case 1:
                reportSender.sendHourlyReport(args[0], now);
                break;
            default:
                if (args[0].equalsIgnoreCase("hourly") || args[0].equalsIgnoreCase("daily")) {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyMMddHHmm");
                    now = LocalDateTime.parse(args[0], formatter);
                    reportSender.sendHourlyReport(args[0], now);
                } else if (args[0].equals("encrypt")) {
                    String seed = this.getClass().getName();  // Keep this secure
                    PasswordEncryption ePwd = new PasswordEncryption();
                    System.out.println("Password: " + ePwd.encrypt(args[1], seed));
                }
                else if (args[0].equals("decrypt")) {
                    String seed = this.getClass().getName();  // Keep this secure
                    PasswordEncryption ePwd = new PasswordEncryption();
                    System.out.println("Password: " + ePwd.decrypt(args[1], seed));

                }
//                else
//                    System.out.println("Wrong arguments: Usages <report_type> <daytime> :: report_type: 'hourly'/'daily', daytime:'yyMMddHHmm' format");
        }


    }

    private void sendHourlyReport(String mode, LocalDateTime now) throws Exception {
        LocalDateTime fromTime, toTime;
        String csvFilePath, sql, subject, body;
        switch (mode) {
            case "hourly":
                toTime = now.withHour(getToHour(now)).withMinute(0).withSecond(0);

                int[] listHours = parseZones();
                int range = listHours[1] - listHours[0];
                fromTime = toTime.minusHours(range);

                csvFilePath = "rpt_" + toTime.format(yyMMdd) + "_" + fromTime.getHour() + "_" + toTime.getHour() + ".csv";
                sql = getSQLHourly(fromTime, toTime);

                subject = properties.getProperty("hourly.mail.subject");
                body = properties.getProperty("hourly.mail.body");
                break;

            case "daily":
                toTime = now.withHour(0).withMinute(0).withSecond(0);
                String reportDays = properties.getProperty("daily.reportDays");
                fromTime = toTime.minusDays(Integer.parseInt(reportDays));

                csvFilePath = "rpt_" + fromTime.format(yyMMdd) + "_" + toTime.format(yyMMdd) + ".csv";
                sql = getSQLDaily(fromTime, toTime);

                subject = properties.getProperty("daily.mail.subject");
                body = properties.getProperty("daily.mail.body");
                break;
            default:
                throw new Exception("Provide report generation mode ");
        }
        generateCSVFromDatabase(csvFilePath, sql);
        sendEmailWithAttachment(csvFilePath, subject, body);
        new File(csvFilePath).deleteOnExit();
    }


    public void sendEmailWithAttachment(String attachmentPath, String subject, String body) {
        String ccEmail = properties.getProperty("cc.mail");
        String recipientEmail = properties.getProperty("recipient.mail.to");
        String senderEmail = properties.getProperty("mail.sender");
        String authPass = properties.getProperty("mail.smtp.authPass");
        try {
            // Create session
            Session session = Session.getInstance(properties, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(senderEmail, authPass);
                }
            });

            // Create a message
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(senderEmail));

            String[] recipients = recipientEmail.split(",");
            Address[] addresses = new Address[recipients.length];
            for (int i = 0; i < recipients.length; i++)
                addresses[i] = new InternetAddress(recipients[i]);
            message.addRecipients(Message.RecipientType.TO, addresses);

            // Set CC recipients
            if (ccEmail != null && !ccEmail.isEmpty()) {
                String[] ccRecipients = ccEmail.split(",");
                Address[] ccAddresses = new Address[ccRecipients.length];
                for (int i = 0; i < ccRecipients.length; i++)
                    ccAddresses[i] = new InternetAddress(ccRecipients[i]);
                message.addRecipients(Message.RecipientType.CC, ccAddresses);
            }

            message.setSubject(subject);

            MimeBodyPart messageBodyPart = new MimeBodyPart();
            messageBodyPart.setText(body);

            MimeBodyPart attachmentPart = new MimeBodyPart();
            attachmentPart.attachFile(attachmentPath);

            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(messageBodyPart);
            multipart.addBodyPart(attachmentPart);

            message.setContent(multipart);

            Transport.send(message);   // Send the email
            System.out.println("Email sent successfully to " + recipientEmail);
            logger.info("Email sent successfully to {}", recipientEmail);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {

        }
    }

    public String getSQLHourly(LocalDateTime start, LocalDateTime end) {
        int callCount = Integer.parseInt(properties.getProperty("hourly.call.count"));
        int smsCount = Integer.parseInt(properties.getProperty("hourly.sms.count"));
        int duration = Integer.parseInt(properties.getProperty("hourly.call.dur"));
        int gprsVol = Integer.parseInt(properties.getProperty("hourly.gprs.vol"));
        String startTime = start.format(dateTimeFormatter);
        String endTime = end.format(dateTimeFormatter);
        String partition = start.format(yyyyMMdd);


        Map<String, Object> params = new HashMap<>();
        params.put("startTime", startTime);
        params.put("endTime", endTime);
        params.put("partition", partition);
        params.put("callCount", callCount);
        params.put("smsCount", smsCount);
        params.put("duration", duration);
        params.put("gprsVol", gprsVol);
        StringSubstitutor substitute = new StringSubstitutor(params);

        String sqlTmpl = properties.getProperty("hourly.rpt.sql");
        return substitute.replace(sqlTmpl);
    }

    public String getSQLDaily(LocalDateTime start, LocalDateTime end) {
        int callCount = Integer.parseInt(properties.getProperty("hourly.call.count"));
        int smsCount = Integer.parseInt(properties.getProperty("hourly.sms.count"));
        int duration = Integer.parseInt(properties.getProperty("hourly.call.dur"));
        int gprsVol = Integer.parseInt(properties.getProperty("hourly.gprs.vol"));
        String startPartition = start.format(yyyyMMdd);
        String endPartition = end.format(yyyyMMdd);

        Map<String, Object> params = new HashMap<>();
        params.put("startPartition", startPartition);
        params.put("endPartition", endPartition);
        params.put("callCount", callCount);
        params.put("duration", duration);
        params.put("smsCount", smsCount);
        params.put("gprsVol", gprsVol);

        String sqlTmpl = properties.getProperty("daily.rpt.sql");
        StringSubstitutor substitute = new StringSubstitutor(params);
        return substitute.replace(sqlTmpl);
    }

    public void generateCSVFromDatabase(String filePath, String query) throws
            SQLException, IOException, ClassNotFoundException {
        Class.forName(properties.getProperty("db.driver"));
        String DB_URL = properties.getProperty("db.url");
        String DB_USER = properties.getProperty("db.user");
        String DB_PASSWORD = properties.getProperty("db.pass");

        Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
        Statement statement = connection.createStatement();

        ResultSet resultSet = statement.executeQuery(query);

        FileWriter csvWriter = new FileWriter(filePath);

        // Write header row
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        for (int i = 1; i <= columnCount; i++) {
            csvWriter.append(metaData.getColumnName(i));
            if (i < columnCount) csvWriter.append(",");
        }
        csvWriter.append("\n");

        // Write data rows
        while (resultSet.next()) {
            for (int i = 1; i <= columnCount; i++) {
                csvWriter.append(resultSet.getString(i));
                if (i < columnCount) csvWriter.append(",");
            }
            csvWriter.append("\n");
        }

        csvWriter.flush();
        csvWriter.close();
        connection.close();
    }

    public int getToHour(LocalDateTime timestamp) {
        int hour = timestamp.getHour();
        int lastHour = 0;
        int[] hours = parseZones();
        for (int h : hours) {
            if (hour >= h)
                lastHour = h;
            else
                break;
        }
        return lastHour;
    }

    private int[] parseZones() {
        String hours = properties.getProperty("hourly.rpt.hours");
        String[] hoursAsStrings = hours.split(",");
        int[] zones = new int[hoursAsStrings.length];
        for (int i = 0; i < hoursAsStrings.length; i++)
            zones[i] = Integer.parseInt(hoursAsStrings[i].trim());
        return zones;
    }


    class PasswordEncryption {

        // Generate key from the secret seed
        public SecretKey generateKey(String seed) throws NoSuchAlgorithmException {
            // Use SHA-256 to create a 256-bit key
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes());
            return new SecretKeySpec(hash, "AES");  // Return as AES key
        }

        // Encrypt the password
        public String encrypt(String password, String seed) throws Exception {
            SecretKey key = generateKey(seed);
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(password.getBytes());
            return Base64.getEncoder().encodeToString(encrypted);  // Return base64-encoded string
        }

        // Decrypt the password
        public String decrypt(String encryptedPassword, String seed) throws Exception {
            SecretKey key = generateKey(seed);
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decodedBytes = Base64.getDecoder().decode(encryptedPassword);
            byte[] decrypted = cipher.doFinal(decodedBytes);
            return new String(decrypted);  // Return decrypted password as a string
        }


    }

}


