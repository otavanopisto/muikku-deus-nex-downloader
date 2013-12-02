package fi.muikku.dnm.downloader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSOutput;
import org.w3c.dom.ls.LSSerializer;
import org.xml.sax.SAXException;

public class Downloader {
  
  public static void main(String[] args) {
    CommandLineParser parser = new GnuParser();
    Options options = new Options();
    
    options.addOption("f", "folder-no", true, "Folder's resource number");
    options.addOption("i", "import-url", true, "Import url. Must contain %s (will be replaced with folder-no)");
    options.addOption("n", "name", true, "Name of deus nex document");
    options.addOption("u", "nexus-user", true, "Nexus username");
    options.addOption("p", "nexus-password", true, "Nexus password");
    options.addOption("z", "zip-password", true, "Zip password");
    options.addOption("d", "output-dir", true, "Target folder");
    options.addOption("r", "binary-lookup", true, "Binary lookup file");
    options.addOption("s", "save-binary-lookup", false, "Indicates that binary file should be saved after import");
    options.addOption("c", "cache-folder", true, "Folder to be used as cache folder");
    
    CommandLine line = null;
    try {
      line = parser.parse(options, args);
    } catch (ParseException e) {
      e.printStackTrace();
      System.err.println("Argument parsing failed");
      System.exit(1);
    }
    
    if (!validateOptions(line)) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("downloader", options);
    } else {
      String folderNo = line.getOptionValue("folder-no");
      String name = line.getOptionValue("name");
      String nexusUser = line.getOptionValue("nexus-user");
      String nexusPassword = line.getOptionValue("nexus-password");
      String zipPassword = line.getOptionValue("zip-password");
      String outputDir = line.getOptionValue("output-dir");
      String importUrl = String.format(line.getOptionValue("import-url"), folderNo);
      String binaryLookupFile = null;
      File cacheFolder = null;
      if (line.hasOption("cache-folder")) {
        cacheFolder = new File(line.getOptionValue("cache-folder"));
        if (!cacheFolder.exists()) {
          cacheFolder.mkdirs();
        }
      }
      
      if (line.hasOption("binary-lookup")) {
        binaryLookupFile = line.getOptionValue("binary-lookup");
      }
      
      boolean saveBinaryLookup = line.hasOption("save-binary-lookup");

      Properties binaryLookup = new Properties();
      if (binaryLookupFile != null) {
        try {
          File file = new File(binaryLookupFile);
          if (!file.exists()) {
            file.createNewFile();
          }
          
          FileReader fileReader = new FileReader(file);
          try {
            binaryLookup.load(fileReader);
          } finally {
            fileReader.close();
          }
        } catch (IOException e) {
          e.printStackTrace();
          System.err.println("Binary lookup parsing failed");
          System.exit(1); 
        }
      }
      
      System.out.println("Downloading deus nex document...");
      Document document = null;
      try {
        document = downloadXmlDocument(importUrl, cacheFolder, nexusUser, nexusPassword);
      } catch (SAXException | IOException | ParserConfigurationException e) {
        e.printStackTrace();
        System.err.println("Deus nex document downloading failed");
        System.exit(1); 
      }
      
      System.out.println("Processing xml data...");
      try {
        processDocument(document, binaryLookup);
      } catch (XPathExpressionException e) {
        e.printStackTrace();
        System.err.println("Document processing failed");
        System.exit(1); 
      }
      
      System.out.println("Serializing xml document...");
      String xmlData = null;
      try {
        xmlData = serializeDocument(document);
      } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | ClassCastException e) {
        e.printStackTrace();
        System.err.println("Document serialization failed");
        System.exit(1); 
      }
      
      System.out.println("Writing xml file...");
      File xmlFile = null;
      try {
        xmlFile = writeXml(xmlData, outputDir, name);
      } catch (IOException e) {
        e.printStackTrace();
        System.err.println("Xml file writing failed");
        System.exit(1); 
      }
      
      System.out.println("Compressing xml file...");
      try {
        compressXmlFile(xmlFile, outputDir, name, zipPassword);
      } catch (IOException | ZipException e) {
        e.printStackTrace();
        System.err.println("Xml file compression failed");
        System.exit(1); 
      }
      
      if (saveBinaryLookup) {
        System.out.println("Saving binary lookup file...");
        try {
          FileWriter fileWriter = new FileWriter(binaryLookupFile);
          try {
            binaryLookup.store(fileWriter, null);
          } finally {
            fileWriter.flush();
            fileWriter.close();
          }
        } catch (IOException e) {
          e.printStackTrace();
          System.err.println("Binary lookup saving failed");
          System.exit(1); 
        }
      }
      
      System.out.println("Import successful");
    }
  }

  private static void processDocument(Document document, Properties binaryLookup) throws XPathExpressionException {
    List<Element> dropElements = new ArrayList<>();
    Map<String, String> resourceMap = new HashMap<>();
    int droppedBinaries = 0;
    int bytesSaved = 0;
     
    NodeList resourceNodes = document.getElementsByTagName("res");
    for (int i = 0, l = resourceNodes.getLength(); i < l; i++) {
      Node resourceNode = resourceNodes.item(i);
      if (resourceNode instanceof Element) {
        Element resourceElement = (Element) resourceNode;
        
        if ("3".equals(getChildNodeValue(resourceElement, "type"))) {
          // Binary resource
          String no = getChildNodeValue(resourceElement, "no");
          String content = getChildNodeValue(resourceElement, "content");
          String contentHash = DigestUtils.md5Hex(content);
          if (binaryLookup.containsKey(contentHash)) {
            bytesSaved += content.length();
            droppedBinaries++;
            resourceMap.put(no, binaryLookup.getProperty(contentHash));
            dropElements.add(resourceElement);
          } else {
            binaryLookup.put(contentHash, no);
          }
        }
      }
    }
    
    for (Element dropElement : dropElements) {
      if (dropElement.getParentNode() != null) {
        dropElement.getParentNode().removeChild(dropElement);
      }
    }
    
    NodeList embeddedItems = document.getElementsByTagName("ix:embeddeditem");
    for (int i = 0, l = embeddedItems.getLength(); i < l; i++) {
      Node embeddedItem = embeddedItems.item(i);
      
      Node resourceNoNode = findNodeByXPath(embeddedItem, "parameters/resourceno");
      if (resourceNoNode instanceof Element) {
        String newResourceNo = resourceMap.get(resourceNoNode.getTextContent());
        if (newResourceNo != null) {
          resourceNoNode.setTextContent(newResourceNo);
        }
      } else {
        System.out.println("Warning could not find resourceno from ix:embeddeditem");
      }
    }
    
    if (droppedBinaries > 0) {
      System.out.println("  " + droppedBinaries + " binaries droppped, approx. " + FileUtils.byteCountToDisplaySize(bytesSaved) + " saved");
    }
  }

  private static boolean validateOptions(CommandLine line) {
    String[] requiredOptions = {"folder-no", "import-url", "name", "nexus-user", "nexus-password", "zip-password", "output-dir"}; 
    for (String requiredOption : requiredOptions) {
      if (!line.hasOption(requiredOption)) {
        System.out.println("Required option " + requiredOption + " is missing");
        return false;
      }
    }
    
    return true;
  }

  private static void compressXmlFile(File xmlFile, String outputDir, String outputFile, String zipPassword) throws IOException, ZipException {
    File outDir = new File(outputDir);
    File zipFile = new File(outDir, outputFile + ".zip");
    if (zipFile.exists()) {
      zipFile.delete();
    }

    ZipParameters parameters = new ZipParameters();

    // Compression
    parameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
    parameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);

    // Encryption
    parameters.setAesKeyStrength(Zip4jConstants.AES_STRENGTH_256);
    parameters.setEncryptionMethod(Zip4jConstants.ENC_METHOD_AES);
    parameters.setEncryptFiles(true);
    parameters.setPassword(zipPassword);

    ZipFile zipArchive = new ZipFile(zipFile);
    zipArchive.createZipFile(xmlFile, parameters);
  }

  private static File writeXml(String xmlData, String outputDir, String outputFile) throws UnsupportedEncodingException, IOException {
    File outDir = new File(outputDir);
    File xmlFile = new File(outDir, outputFile + ".xml");
    if (xmlFile.exists()) {
      xmlFile.delete();
    }
    
    xmlFile.createNewFile();

    FileOutputStream fos = new FileOutputStream(xmlFile);
    fos.write(xmlData.getBytes("UTF-8"));
    fos.flush();
    fos.close();

    return xmlFile;
  }

  private static Document downloadXmlDocument(String importUrl, File cacheDir, String nexusUser, String nexusPassword) throws SAXException, IOException, ParserConfigurationException {
    File cacheFile = null;
    byte[] data = null;

    if (cacheDir != null) {
      String cacheFileName = DigestUtils.md5Hex(importUrl);
      cacheFile = new File(cacheDir, cacheFileName);
      if (cacheFile.exists()) {
        FileInputStream fileInputStream = new FileInputStream(cacheFile);
        try {
          data = IOUtils.toByteArray(fileInputStream);
        } finally {
          fileInputStream.close();
        }
      }
    } 
    
    if (data == null) {
      String authorization = Base64.encodeBase64String((nexusUser + ':' + nexusPassword).getBytes("UTF-8"));
      URL url = new URL(importUrl);
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestProperty("Authorization", "Basic " + authorization);
  
      InputStream inputStream = connection.getInputStream();
      try {
        data = IOUtils.toByteArray(inputStream);
      } finally {
        inputStream.close();
      }
        
      if (cacheDir != null) {
        FileOutputStream fileOutputStream = new FileOutputStream(cacheFile);
        try {
          fileOutputStream.write(data);
        } finally {
          fileOutputStream.flush();
          fileOutputStream.close();
        }
      }
    }

    return parseXml(data);
  }

  private static Document parseXml(byte[] data) throws SAXException, IOException, ParserConfigurationException  {
    DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
    builderFactory.setNamespaceAware(false);
    builderFactory.setValidating(false);
    builderFactory.setFeature("http://xml.org/sax/features/namespaces", false);
    builderFactory.setFeature("http://xml.org/sax/features/validation", false);
    builderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
    builderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    DocumentBuilder builder = builderFactory.newDocumentBuilder();
   
    ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
    try {
      return builder.parse(inputStream);
    } finally {
      inputStream.close();
    }
  }
  
  private static String serializeDocument(Document document) throws ClassNotFoundException, InstantiationException, IllegalAccessException, ClassCastException {
    DOMImplementationRegistry registry = DOMImplementationRegistry.newInstance();
    DOMImplementationLS impl = (DOMImplementationLS) registry.getDOMImplementation("LS");
    LSSerializer writer = impl.createLSSerializer();
    writer.getDomConfig().setParameter("format-pretty-print", Boolean.TRUE);
    LSOutput output = impl.createLSOutput();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    output.setByteStream(out);
    writer.write(document, output);
    return new String(out.toByteArray());
  }

  private static String getChildNodeValue(Element parent, String name) throws XPathExpressionException {
    Node node = findNodeByXPath(parent, name);
    if (node instanceof Element) {
      return ((Element) node).getTextContent();
    }
    
    return null;
  }
  
  @SuppressWarnings("unused")
  private static NodeList findNodesByXPath(Node contextNode, String expression) throws XPathExpressionException {
    return (NodeList) XPathFactory.newInstance().newXPath().evaluate(expression, contextNode, XPathConstants.NODESET);
  }
  
  private static Node findNodeByXPath(Node contextNode, String expression) throws XPathExpressionException {
    return (Node) XPathFactory.newInstance().newXPath().evaluate(expression, contextNode, XPathConstants.NODE);
  }
}
