SET FOREIGN_KEY_CHECKS = 0;
CREATE DATABASE  IF NOT EXISTS `db2` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;
USE `arraysdb`;
-- MySQL dump 10.13  Distrib 8.0.45, for Win64 (x86_64)
--
-- Host: 127.0.0.1    Database: db2
-- ------------------------------------------------------
-- Server version	8.0.45

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `accountverifications`
--

DROP TABLE IF EXISTS `accountverifications`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `accountverifications` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `url` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UQ_AccountVerifications_User_Id` (`user_id`),
  UNIQUE KEY `UQ_AccountVerifications_Url` (`url`),
  CONSTRAINT `accountverifications_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `accountverifications`
--

LOCK TABLES `accountverifications` WRITE;
/*!40000 ALTER TABLE `accountverifications` DISABLE KEYS */;
INSERT INTO `accountverifications` VALUES (10,13,'http://localhost:8080/user/verify/account/30e86e76-7f5f-4dbd-8bf8-aaa5ee06753c'),(11,14,'http://localhost:8080/user/verify/account/f501f17a-14c9-40ee-bcfd-8c134f81f6bf'),(12,15,'http://localhost:8080/user/verify/account/b7e85fba-7978-4dd1-949a-d599bd76d165'),(13,16,'http://localhost:8080/user/verify/account/6f45bf9c-09c2-4ea1-97b0-1bb0b3c8fd15');
/*!40000 ALTER TABLE `accountverifications` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `customer`
--

DROP TABLE IF EXISTS `customer`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `customer` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `createdAt` datetime(6) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `imageUrl` varchar(255) DEFAULT NULL,
  `customer_name` varchar(255) DEFAULT NULL,
  `phoneNumber` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `type` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=105 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `customer`
--

LOCK TABLES `customer` WRITE;
/*!40000 ALTER TABLE `customer` DISABLE KEYS */;
INSERT INTO `customer` VALUES (1,'1234 Any Street, Kauai, HI, 96716','2026-05-18 19:35:03.000000','bobbytest@yopmail.com','','Robert Oliver1','123','INACTIVE','INDIVIDUAL'),(2,'1234 Any Street, Kauai, HI, 96716','2026-05-18 19:35:03.000000','bobbytest@yopmail.com','','Robert Oliver','1234','PENDING','ENTERPRISE'),(3,'1234 Any Street, Kauai, HI, 96716','2026-05-18 19:35:03.000000','bobbytest@yopmail.com','','Robert Oliver','1234','BANNED','ENTERPRISE'),(4,'1234 Any Street, Kauai, HI, 96716','2026-05-18 19:35:03.000000','bobbytest@yopmail.com','','Robert Oliver','1234','ACTIVE','INDIVIDUAL'),(5,'612 Fairview Center','2026-05-18 19:35:03.000000','hmatresse4@kickstarter.com',NULL,'Hermann Matresse','2707813242','INACTIVE','CORPORATE'),(6,'7 Sheridan Junction','2026-05-18 19:35:03.000000','gmeasor5@sina.com.cn',NULL,'Gypsy Measor','7324017640','PENDING','INDIVIDUAL'),(7,'5810 Acker Trail','2026-05-18 19:35:03.000000','sramalhete6@blinklist.com',NULL,'Stefanie Ramalhete','2175140327','BANNED','INDIVIDUAL'),(8,'84677 Larry Point','2026-05-18 19:35:03.000000','gduckett7@ed.gov',NULL,'Guilbert Duckett','3748987374','ACTIVE','CORPORATE'),(9,'0 Bowman Pass','2026-05-18 19:35:03.000000','ewhistlecroft8@1und1.de',NULL,'Ezechiel Whistlecroft','5415059648','INACTIVE','GROUP'),(10,'580 Dixon Crossing','2026-05-18 19:35:03.000000','chitcham9@angelfire.com',NULL,'Calida Hitcham','3457511581','PENDING','INDIVIDUAL'),(11,'734 Crowley Way','2026-05-18 19:35:03.000000','rbutsona@blog.com',NULL,'Riannon Butson','9584719035','BANNED','CORPORATE'),(12,'87266 Cordelia Pass','2026-05-18 19:35:03.000000','astranaghanb@e-recht24.de',NULL,'Anselm Stranaghan','3007569647','ACTIVE','ENTERPRISE'),(13,'0036 Barnett Trail','2026-05-18 19:35:03.000000','bmatelaitisc@webmd.com',NULL,'Berri Matelaitis','8169477560','INACTIVE','CORPORATE'),(14,'2 Hermina Street','2026-05-18 19:35:03.000000','jbernoletd@youtube.com',NULL,'Jessa Bernolet','5171007763','PENDING','ENTERPRISE'),(15,'1 Helena Terrace','2026-05-18 19:35:03.000000','obrissone@cnn.com',NULL,'Olly Brisson','6375347175','BANNED','GROUP'),(16,'95 Namekagon Circle','2026-05-18 19:35:03.000000','fbuntf@telegraph.co.uk',NULL,'Ferrel Bunt','9587758795','ACTIVE','CORPORATE'),(17,'9 Upham Trail','2026-05-18 19:35:03.000000','llosemanng@redcross.org',NULL,'Lona Losemann','6444865346','INACTIVE','CORPORATE'),(18,'5005 Evergreen Circle','2026-05-18 19:35:03.000000','dyackiminieh@canalblog.com',NULL,'Danella Yackiminie','1889835820','PENDING','ENTERPRISE'),(19,'49288 Burrows Parkway','2026-05-18 19:35:03.000000','etetlai@shareasale.com',NULL,'Enid Tetla','3761725924','BANNED','GROUP'),(20,'582 Sullivan Circle','2026-05-18 19:35:03.000000','barterj@dagondesign.com',NULL,'Bearnard Arter','4586258074','ACTIVE','GROUP'),(21,'27138 Calypso Road','2026-05-18 19:35:03.000000','talvaradok@ycombinator.com',NULL,'Tracee Alvarado','8569934960','INACTIVE','INDIVIDUAL'),(22,'984 Manitowish Drive','2026-05-18 19:35:03.000000','wgethinsl@google.es',NULL,'Whitney Gethins','4798161586','PENDING','CORPORATE'),(23,'93 Stoughton Court','2026-05-18 19:35:03.000000','swarburtonm@epa.gov',NULL,'Saunderson Warburton','9145152299','BANNED','INDIVIDUAL'),(24,'54 Texas Point','2026-05-18 19:35:03.000000','droaken@wikispaces.com',NULL,'Diana Roake','7207159450','ACTIVE','GROUP'),(25,'5 Montana Park','2026-05-18 19:35:03.000000','gstobbeo@sun.com',NULL,'Gregorio Stobbe','5613118533','INACTIVE','ENTERPRISE'),(26,'23076 Arizona Street','2026-05-18 19:35:03.000000','cstoodleyp@deviantart.com',NULL,'Catie Stoodley','5472533654','PENDING','INDIVIDUAL'),(27,'20425 Sullivan Drive','2026-05-18 19:35:03.000000','ajimpsonq@bandcamp.com',NULL,'Antonio Jimpson','1612398457','BANNED','ENTERPRISE'),(28,'89 Rutledge Drive','2026-05-18 19:35:03.000000','ijezzardr@hp.com',NULL,'Ibby Jezzard','1716312337','ACTIVE','INDIVIDUAL'),(29,'86478 Colorado Lane','2026-05-18 19:35:03.000000','lcrankes@cnet.com',NULL,'Leonerd Cranke','3526649809','INACTIVE','ENTERPRISE'),(30,'0885 Namekagon Crossing','2026-05-18 19:35:03.000000','lhargett@topsy.com',NULL,'Lesley Harget','8318017786','PENDING','GROUP'),(31,'94672 Emmet Alley','2026-05-18 19:35:03.000000','etalletu@1688.com',NULL,'Eleonore Tallet','3815883904','BANNED','CORPORATE'),(32,'8 Miller Road','2026-05-18 19:35:03.000000','weusticev@deliciousdays.com',NULL,'Wilfred Eustice','6942505129','ACTIVE','GROUP'),(33,'20203 Straubel Court','2026-05-18 19:35:03.000000','hfeehanw@sohu.com',NULL,'Harwilll Feehan','3737752763','INACTIVE','CORPORATE'),(34,'8294 Schurz Alley','2026-05-18 19:35:03.000000','gnorresx@ehow.com',NULL,'Graehme Norres','5473695269','PENDING','CORPORATE'),(35,'53252 Mccormick Plaza','2026-05-18 19:35:03.000000','abrashery@state.gov',NULL,'Abbie Brasher','7741771969','BANNED','ENTERPRISE'),(36,'11 Eastwood Road','2026-05-18 19:35:03.000000','wmelrossz@histats.com',NULL,'Walliw Melross','4868046824','ACTIVE','INDIVIDUAL'),(37,'496 Main Way','2026-05-18 19:35:03.000000','csorro10@ox.ac.uk',NULL,'Conway Sorro','1829336530','INACTIVE','ENTERPRISE'),(38,'96456 Sutherland Parkway','2026-05-18 19:35:03.000000','aalliband11@webmd.com',NULL,'Amy Alliband','2427008981','PENDING','ENTERPRISE'),(39,'04 Amoth Center','2026-05-18 19:35:03.000000','jscawton12@geocities.jp',NULL,'Jammie Scawton','1937185340','BANNED','ENTERPRISE'),(40,'8 Comanche Court','2026-05-18 19:35:03.000000','whasely13@bing.com',NULL,'Waldon Hasely','8335384104','ACTIVE','INDIVIDUAL'),(41,'54 Roxbury Alley','2026-05-18 19:35:03.000000','oschirok14@soundcloud.com',NULL,'Ollie Schirok','8673830067','INACTIVE','CORPORATE'),(42,'6 Hoffman Drive','2026-05-18 19:35:03.000000','sgannan15@sbwire.com',NULL,'Silas Gannan','4775940835','PENDING','CORPORATE'),(43,'4 Golden Leaf Lane','2026-05-18 19:35:03.000000','ajakobsson16@biblegateway.com',NULL,'Annemarie Jakobsson','2208614099','BANNED','ENTERPRISE'),(44,'61 Fairfield Road','2026-05-18 19:35:03.000000','gpoinsett17@slideshare.net',NULL,'Griffy Poinsett','1809977276','ACTIVE','INDIVIDUAL'),(45,'508 Meadow Ridge Avenue','2026-05-18 19:35:03.000000','agogay18@smh.com.au',NULL,'Anatol Gogay','2144961100','INACTIVE','GROUP'),(46,'77118 Texas Avenue','2026-05-18 19:35:03.000000','jkubalek19@netscape.com',NULL,'Jillane Kubalek','2894856548','PENDING','ENTERPRISE'),(47,'1406 Sheridan Crossing','2026-05-18 19:35:03.000000','gswepson1a@pinterest.com',NULL,'Georgia Swepson','3214924788','BANNED','INDIVIDUAL'),(48,'288 Summer Ridge Parkway','2026-05-18 19:35:03.000000','ecausbey1b@bigcartel.com',NULL,'Emeline Causbey','8205898623','ACTIVE','INDIVIDUAL'),(49,'669 Garrison Street','2026-05-18 19:35:03.000000','cargontt1c@imdb.com',NULL,'Cobbie Argontt','5138654536','INACTIVE','ENTERPRISE'),(50,'373 High Crossing Plaza','2026-05-18 19:35:03.000000','mstennings1d@liveinternet.ru',NULL,'Mirabel Stennings','4072273357','PENDING','ENTERPRISE'),(51,'440 Fremont Street','2026-05-18 19:35:03.000000','aanglim1e@google.cn',NULL,'Alix Anglim','5367312142','BANNED','ENTERPRISE'),(52,'68 Sheridan Avenue','2026-05-18 19:35:03.000000','mdraycott1f@cargocollective.com',NULL,'Meridel Draycott','3554181921','ACTIVE','ENTERPRISE'),(53,'33848 Elka Street','2026-05-18 19:35:03.000000','cseiler1g@soup.io',NULL,'Cyndia Seiler','4211078954','INACTIVE','GROUP'),(54,'41 Fairview Parkway','2026-05-18 19:35:03.000000','fspread1h@businessinsider.com',NULL,'Fara Spread','6245492016','PENDING','ENTERPRISE'),(55,'7 Katie Terrace','2026-05-18 19:35:03.000000','emcphillimey1i@usnews.com',NULL,'Earlie McPhillimey','3368183569','BANNED','ENTERPRISE'),(56,'72 Sage Way','2026-05-18 19:35:03.000000','drickman1j@instagram.com',NULL,'Devon Rickman','8346902112','ACTIVE','GROUP'),(57,'47415 Blue Bill Park Park','2026-05-18 19:35:03.000000','cchaffyn1k@ed.gov',NULL,'Christa Chaffyn','5507287624','INACTIVE','INDIVIDUAL'),(58,'163 Jana Circle','2026-05-18 19:35:03.000000','akyncl1l@stumbleupon.com',NULL,'Adelina Kyncl','2591598638','PENDING','CORPORATE'),(59,'9 Straubel Avenue','2026-05-18 19:35:03.000000','jrandell1m@altervista.org',NULL,'Juliana Randell','6319536070','BANNED','CORPORATE'),(60,'812 Carberry Hill','2026-05-18 19:35:03.000000','jchampkin1n@google.es',NULL,'Joelly Champkin','1502111430','ACTIVE','INDIVIDUAL'),(61,'763 New Castle Alley','2026-05-18 19:35:03.000000','mmartensen1o@cdbaby.com',NULL,'Megan Martensen','3455704219','INACTIVE','ENTERPRISE'),(62,'3 Ohio Circle','2026-05-18 19:35:03.000000','lcleever1p@go.com',NULL,'Leighton Cleever','6632314322','PENDING','ENTERPRISE'),(63,'829 Ridgeview Terrace','2026-05-18 19:35:03.000000','hcoulthart1q@ow.ly',NULL,'Heywood Coulthart','4065124799','BANNED','ENTERPRISE'),(64,'66 Oak Circle','2026-05-18 19:35:03.000000','nskaife1r@tripod.com',NULL,'Ned Skaife d\'Ingerthorpe','8942666098','ACTIVE','ENTERPRISE'),(65,'525 Portage Terrace','2026-05-18 19:35:03.000000','gshillinglaw1s@time.com',NULL,'Gradeigh Shillinglaw','5141547540','INACTIVE','GROUP'),(66,'856 Daystar Park','2026-05-18 19:35:03.000000','arushforth1t@simplemachines.org',NULL,'Afton Rushforth','8761260659','PENDING','INDIVIDUAL'),(67,'383 Tennyson Place','2026-05-18 19:35:03.000000','lbartalucci1u@weebly.com',NULL,'Lethia Bartalucci','1136331046','BANNED','CORPORATE'),(68,'67052 Schlimgen Parkway','2026-05-18 19:35:03.000000','tminto1v@soundcloud.com',NULL,'Tedi Minto','8635399961','ACTIVE','INDIVIDUAL'),(69,'59436 Hoffman Plaza','2026-05-18 19:35:03.000000','cclulow1w@godaddy.com',NULL,'Cristionna Clulow','8152689478','INACTIVE','ENTERPRISE'),(70,'68 Johnson Parkway','2026-05-18 19:35:03.000000','dwortman1x@chicagotribune.com',NULL,'Dolf Wortman','4358442131','PENDING','GROUP'),(71,'96 Tennyson Drive','2026-05-18 19:35:03.000000','sdi1y@163.com',NULL,'Stillman Di Domenico','7145410901','BANNED','INDIVIDUAL'),(72,'1 Amoth Road','2026-05-18 19:35:03.000000','gbellord1z@hostgator.com',NULL,'Gena Bellord','8862466829','ACTIVE','INDIVIDUAL'),(73,'0 Eagle Crest Avenue','2026-05-18 19:35:03.000000','ebruggen20@reddit.com',NULL,'Estele Bruggen','7025813186','INACTIVE','CORPORATE'),(74,'8811 Holy Cross Drive','2026-05-18 19:35:03.000000','lfiller21@seattletimes.com',NULL,'Livvie Filler','6615201468','PENDING','GROUP'),(75,'57 Evergreen Lane','2026-05-18 19:35:03.000000','holek22@google.de',NULL,'Happy Olek','6286704959','BANNED','GROUP'),(76,'431 Elgar Point','2026-05-18 19:35:03.000000','lmcnee23@ow.ly',NULL,'Lia McNee','1467049968','ACTIVE','CORPORATE'),(77,'061 Montana Way','2026-05-18 19:35:03.000000','ewhiffen24@marriott.com',NULL,'Edward Whiffen','9484809588','INACTIVE','GROUP'),(78,'58547 Blue Bill Park Trail','2026-05-18 19:35:03.000000','kearney25@delicious.com',NULL,'Kacie Earney','1203637517','PENDING','INDIVIDUAL'),(79,'69579 Manitowish Center','2026-05-18 19:35:03.000000','tofener26@google.it',NULL,'Tristam Ofener','8948862988','BANNED','ENTERPRISE'),(80,'59 Reinke Crossing','2026-05-18 19:35:03.000000','fplayfair27@topsy.com',NULL,'Fonzie Playfair','8463758352','ACTIVE','GROUP'),(81,'3 Pawling Place','2026-05-18 19:35:03.000000','lsappson28@cnet.com',NULL,'Lizabeth Sappson','7037375071','INACTIVE','GROUP'),(82,'8 Petterle Court','2026-05-18 19:35:03.000000','mblennerhassett29@example.com',NULL,'Miner Blennerhassett','7702399698','PENDING','GROUP'),(83,'687 Rockefeller Park','2026-05-18 19:35:03.000000','kmatuszyk2a@cpanel.net',NULL,'Kenon Matuszyk','5358434313','BANNED','INDIVIDUAL'),(84,'644 Monterey Point','2026-05-18 19:35:03.000000','cmccaughran2b@netlog.com',NULL,'Cariotta McCaughran','1515528421','ACTIVE','INDIVIDUAL'),(85,'95560 Hollow Ridge Junction','2026-05-18 19:35:03.000000','kbestall2c@simplemachines.org',NULL,'Keefe Bestall','4028793688','INACTIVE','ENTERPRISE'),(86,'4138 Daystar Park','2026-05-18 19:35:03.000000','isiegertsz2d@epa.gov',NULL,'Isidora Siegertsz','2523342157','PENDING','GROUP'),(87,'53601 Merry Hill','2026-05-18 19:35:03.000000','rsarney2e@google.ru',NULL,'Roselin Sarney','4588517780','BANNED','GROUP'),(88,'13413 Springs Crossing','2026-05-18 19:35:03.000000','mlaven2f@stumbleupon.com',NULL,'Melantha Laven','6618360332','ACTIVE','ENTERPRISE'),(89,'997 Anhalt Point','2026-05-18 19:35:03.000000','kloreit2g@auda.org.au',NULL,'Karlan Loreit','9762125610','INACTIVE','INDIVIDUAL'),(90,'1890 Ronald Regan Lane','2026-05-18 19:35:03.000000','asime2h@gov.uk',NULL,'April Sime','5695487921','PENDING','INDIVIDUAL'),(91,'6 Hallows Avenue','2026-05-18 19:35:03.000000','gprettyman2i@delicious.com',NULL,'Garner Prettyman','8238881277','BANNED','INDIVIDUAL'),(92,'6546 Reindahl Trail','2026-05-18 19:35:03.000000','gwestphal2j@si.edu',NULL,'Godfree Westphal','9316645348','ACTIVE','INDIVIDUAL'),(93,'73 Park Meadow Parkway','2026-05-18 19:35:03.000000','mfowkes2k@paypal.com',NULL,'Marcus Fowkes','5656845653','INACTIVE','GROUP'),(94,'724 Warrior Road','2026-05-18 19:35:03.000000','mkesey2l@marketwatch.com',NULL,'Mitchael Kesey','4106976724','PENDING','GROUP'),(95,'6978 Mallard Street','2026-05-18 19:35:03.000000','icosker2m@edublogs.org',NULL,'Inge Cosker','1527454062','BANNED','GROUP'),(96,'8 Cordelia Junction','2026-05-18 19:35:03.000000','bdavidson2n@digg.com',NULL,'Bartlett Davidson','4935006457','ACTIVE','CORPORATE'),(97,'4 Red Cloud Alley','2026-05-18 19:35:03.000000','ktitterington2o@lulu.com',NULL,'Kinna Titterington','1508313992','INACTIVE','GROUP'),(98,'4110 Meadow Vale Park','2026-05-18 19:35:03.000000','dbenedidick2p@admin.ch',NULL,'Domenico Benedidick','1086760709','PENDING','INDIVIDUAL'),(99,'63 Glacier Hill Pass','2026-05-18 19:35:03.000000','couldcott2q@washington.edu',NULL,'Conroy Ouldcott','3815247377','BANNED','INDIVIDUAL'),(100,'42737 Mesta Terrace','2026-05-18 19:35:03.000000','enoir2r@pagesperso-orange.fr',NULL,'Ethelyn Noir','9362710043','ACTIVE','ENTERPRISE'),(101,'123 Any Street','2026-05-18 19:35:03.000000','newbobbylon@yopmail.com','https://images.unsplash.com/photo-1771132666487-3d7a048a36df?w=600&auto=format&fit=crop&q=60&ixlib=rb-4.1.0&ixid=M3wxMjA3fDB8MHxmZWF0dXJlZC1waG90b3MtZmVlZHwzfHx8ZW58MHx8fHx8','newcustomerformtest',NULL,'ACTIVE','INDIVIDUAL'),(102,'123 Any Street','2026-05-18 19:35:03.000000','newbobbylo1n@yopmail.com','https://images.unsplash.com/photo-1771132666487-3d7a048a36df?w=600&auto=format&fit=crop&q=60&ixlib=rb-4.1.0&ixid=M3wxMjA3fDB8MHxmZWF0dXJlZC1waG90b3MtZmVlZHwzfHx8ZW58MHx8fHx8','newcustomerformtest11',NULL,'PENDING','INDIVIDUAL'),(103,'123 Any Street','2026-05-18 19:35:03.000000','newbobbylo1n@yopmail.com','https://images.unsplash.com/photo-1771132666487-3d7a048a36df?w=600&auto=format&fit=crop&q=60&ixlib=rb-4.1.0&ixid=M3wxMjA3fDB8MHxmZWF0dXJlZC1waG90b3MtZmVlZHwzfHx8ZW58MHx8fHx8','newcustomerformtest11',NULL,'PENDING','INDIVIDUAL'),(104,'123 Cool Street','2026-05-18 19:35:03.000000','coolcompany127@yopmail.com','https://images.unsplash.com/photo-1771132666487-3d7a048a36df?w=600&auto=format&fit=crop&q=60&ixlib=rb-4.1.0&ixid=M3wxMjA3fDB8MHxmZWF0dXJlZC1waG90b3MtZmVlZHwzfHx8ZW58MHx8fHx8','a cool company yo','123456789','ACTIVE','INDIVIDUAL');
/*!40000 ALTER TABLE `customer` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `events`
--

DROP TABLE IF EXISTS `events`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `events` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `type` varchar(50) NOT NULL,
  `description` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UQ_Events_Type` (`type`),
  CONSTRAINT `events_chk_1` CHECK ((`type` in (_utf8mb4'LOGIN_ATTEMPT',_utf8mb4'LOGIN_ATTEMPT_FAILURE',_utf8mb4'LOGIN_ATTEMPT_SUCCESS',_utf8mb4'PROFILE_UPDATE',_utf8mb4'PROFILE_PICTURE_UPDATE',_utf8mb4'ROLE_UPDATE',_utf8mb4'ACCOUNT_SETTINGS_UPDATE',_utf8mb4'PASSWORD_UPDATE',_utf8mb4'MFA_UPDATE')))
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `events`
--

LOCK TABLES `events` WRITE;
/*!40000 ALTER TABLE `events` DISABLE KEYS */;
INSERT INTO `events` VALUES (1,'LOGIN_ATTEMPT','You tried to log-in :)'),(2,'LOGIN_ATTEMPT_SUCCESS','You attempted to log-in and you succeeded :)'),(3,'LOGIN_ATTEMPT_FAILURE','You tried to log-in, but you failed to do so :('),(4,'PROFILE_UPDATE','You have updated your profile information :)'),(5,'PROFILE_PICTURE_UPDATE','You have updated your profile picture :)'),(6,'ROLE_UPDATE','You have updated your role and permissions :)'),(7,'ACCOUNT_SETTINGS_UPDATE','You have updated your account settings :)'),(8,'PASSWORD_UPDATE','You have updated your password successfully :)'),(9,'MFA_UPDATE','You have updated your multi-factor authentication settings :)');
/*!40000 ALTER TABLE `events` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `flyway_schema_history`
--

DROP TABLE IF EXISTS `flyway_schema_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flyway_schema_history` (
  `installed_rank` int NOT NULL,
  `version` varchar(50) DEFAULT NULL,
  `description` varchar(200) NOT NULL,
  `type` varchar(20) NOT NULL,
  `script` varchar(1000) NOT NULL,
  `checksum` int DEFAULT NULL,
  `installed_by` varchar(100) NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `flyway_schema_history_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `flyway_schema_history`
--

LOCK TABLES `flyway_schema_history` WRITE;
/*!40000 ALTER TABLE `flyway_schema_history` DISABLE KEYS */;
INSERT INTO `flyway_schema_history` VALUES (1,'0','<< Flyway Baseline >>','BASELINE','<< Flyway Baseline >>',NULL,'root','2026-05-21 17:53:23',0,1),(2,'1','baseline schema','SQL','V1__baseline_schema.sql',-1850964772,'root','2026-05-21 17:53:23',71,0);
/*!40000 ALTER TABLE `flyway_schema_history` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `invoice`
--

DROP TABLE IF EXISTS `invoice`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `invoice` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` double DEFAULT NULL,
  `customerId` bigint DEFAULT NULL,
  `invoiceDate` datetime(6) DEFAULT NULL,
  `invoiceNumber` varchar(255) DEFAULT NULL,
  `service` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `totalAmount` double DEFAULT NULL,
  `customer` bigint NOT NULL,
  `services` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK5v0hotbfco9ybvphaskamsvpy` (`customer`),
  CONSTRAINT `FK5v0hotbfco9ybvphaskamsvpy` FOREIGN KEY (`customer`) REFERENCES `customer` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `invoice`
--

LOCK TABLES `invoice` WRITE;
/*!40000 ALTER TABLE `invoice` DISABLE KEYS */;
INSERT INTO `invoice` VALUES (1,NULL,1,'2024-01-15 00:00:00.000000','INV-2024-0001',NULL,'PAID',4500.11,1,NULL),(2,NULL,1,'2024-02-20 00:00:00.000000','INV-2024-0002',NULL,'OVERDUE',1200.43,1,NULL),(3,NULL,2,'2024-01-05 00:00:00.000000','INV-2024-0003',NULL,'OVERDUE',3800.45,2,NULL),(4,NULL,2,'2024-03-10 00:00:00.000000','INV-2024-0004',NULL,'ACTIVE',2100.83,2,NULL),(5,NULL,3,'2024-02-01 00:00:00.000000','INV-2024-0005',NULL,'PAID',6750.91,3,NULL),(6,NULL,3,'2024-03-15 00:00:00.000000','INV-2024-0006',NULL,'CANCELED',950.47,3,NULL),(7,NULL,1,'2024-04-01 00:00:00.000000','INV-2024-0007',NULL,'ACTIVE',3300.86,1,NULL),(8,NULL,1,'2026-05-15 17:00:00.000000','QHZMZ9WDYO',NULL,'CANCELED',123.12,1,NULL);
/*!40000 ALTER TABLE `invoice` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `invoiceserviceitems`
--

DROP TABLE IF EXISTS `invoiceserviceitems`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `invoiceserviceitems` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `invoice_id` bigint NOT NULL,
  `item_order` int NOT NULL DEFAULT 0,
  `name` varchar(255) DEFAULT NULL,
  `price` double DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK42jlmerqgcuhreoeenij9q9bb` (`invoice_id`),
  CONSTRAINT `FK42jlmerqgcuhreoeenij9q9bb` FOREIGN KEY (`invoice_id`) REFERENCES `invoice` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `invoiceserviceitems`
--

LOCK TABLES `invoiceserviceitems` WRITE;
/*!40000 ALTER TABLE `invoiceserviceitems` DISABLE KEYS */;
INSERT INTO `invoiceserviceitems` (`invoice_id`, `item_order`, `name`, `price`) VALUES (1,0,'Web Development',3000),(1,1,'UI/UX Design',1500),(2,0,'Consulting',1200),(3,0,'Server Migration',2500),(3,1,'Security Audit',1300),(4,0,'Monthly Retainer',2100),(5,0,'Mobile App Development',5000),(5,1,'App Store Submission',750),(6,0,'Logo Design',950),(7,0,'SEO Optimization',1800),(7,1,'Content Writing',1500),(8,0,'The New 2026 Car Detailing Service',2);
/*!40000 ALTER TABLE `invoiceserviceitems` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `resetpasswordverifications`
--

DROP TABLE IF EXISTS `resetpasswordverifications`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `resetpasswordverifications` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `url` varchar(255) NOT NULL,
  `expiration_date` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UQ_ResetPasswordVerifications_User_Id` (`user_id`),
  UNIQUE KEY `UQ_ResetPasswordVerifications_Url` (`url`),
  CONSTRAINT `resetpasswordverifications_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `resetpasswordverifications`
--

LOCK TABLES `resetpasswordverifications` WRITE;
/*!40000 ALTER TABLE `resetpasswordverifications` DISABLE KEYS */;
/*!40000 ALTER TABLE `resetpasswordverifications` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `roles`
--

DROP TABLE IF EXISTS `roles`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `roles` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `permission` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UQ_Roles_Name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=30 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `roles`
--

LOCK TABLES `roles` WRITE;
/*!40000 ALTER TABLE `roles` DISABLE KEYS */;
INSERT INTO `roles` VALUES (1,'ROLE_USER','READ:USER, READ:CUSTOMER'),(2,'ROLE_MANAGER','READ:USER, READ:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER'),(3,'ROLE_ADMIN','READ:USER, READ:CUSTOMER, CREATE:USER, CREATE:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER'),(4,'ROLE_HELP_DESK_ADMIN','READ:USER, READ:CUSTOMER, CREATE:USER, CREATE:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER, DELETE:USER, DELETE:CUSTOMER');
/*!40000 ALTER TABLE `roles` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `services`
--

DROP TABLE IF EXISTS `services`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `services` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `description` varchar(255) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `price` double DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=41 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `services`
--

LOCK TABLES `services` WRITE;
/*!40000 ALTER TABLE `services` DISABLE KEYS */;
INSERT INTO `services` VALUES (1,'Full interior and exterior vehicle detail','Car Detailing',149.99),(2,'Standard oil and filter change service','Oil Change',49.99),(3,'Rotate and balance all four tires','Tire Rotation',39.99),(4,'Full brake system inspection and report','Brake Inspection',59.99),(5,'OEM windshield replacement with seal','Windshield Replacement',299.99),(6,'Hand wash, wax, and interior vacuum','Car Wash (Premium)',79.99),(7,'General plumbing repairs and leak fixes','Plumbing Repair',120),(8,'Unclog and clean residential drains','Drain Cleaning',89),(9,'Full home electrical safety inspection','Electrical Inspection',175),(10,'Install new outlets or switches','Outlet Installation',85),(11,'Seasonal heating and cooling system tune-up','HVAC Tune-Up',129),(12,'Diagnose and repair air conditioning units','AC Repair',199),(13,'Full residential roof inspection and report','Roof Inspection',149),(14,'Clean and flush all gutters and downspouts','Gutter Cleaning',99),(15,'Pressure wash driveway, deck, or siding','Pressure Washing',119),(16,'Interior and exterior window cleaning','Window Cleaning',89),(17,'Standard full-home cleaning service','House Cleaning',150),(18,'Full deep-clean including appliances and baseboards','Deep Clean',250),(19,'Inspect and treat for common household pests','Pest Control',120),(20,'Mow, edge, trim, and blow lawn and garden areas','Landscaping',110),(21,'Prune and shape trees and large shrubs','Tree Trimming',200),(22,'Clear driveway, walkways, and steps','Snow Removal',75),(23,'Install wood or vinyl privacy fence (per section)','Fence Installation',250),(24,'Paint one room, including prep and cleanup','Interior Painting',350),(25,'Standard haircut for men or women','Haircut',35),(26,'Full hair color treatment','Hair Coloring',95),(27,'Relaxation or deep-tissue massage, 60 minutes','Massage (60 min)',90),(28,'One-on-one personal training session, 1 hour','Personal Training',75),(29,'Bath, blow-dry, trim, and nail clip','Pet Grooming',65),(30,'One-hour dog walk','Dog Walking',25),(31,'In-home childcare per hour','Babysitting',20),(32,'One-on-one academic tutoring session, 1 hour','Tutoring',50),(33,'Replace cracked smartphone screen','Phone Screen Repair',99),(34,'Diagnose and repair desktop or laptop','Computer Repair',125),(35,'Remove malware and optimize PC performance','Virus Removal',89),(36,'Install and configure smart home devices','Smart Home Setup',175),(37,'Individual tax return preparation and filing','Tax Preparation',200),(38,'Notarize documents, per signature','Notary Service',15),(39,'One-hour professional photo session','Photography Session',250),(40,'Edit and produce a short video up to 5 minutes','Video Editing',300);
/*!40000 ALTER TABLE `services` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `twofactorverifications`
--

DROP TABLE IF EXISTS `twofactorverifications`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `twofactorverifications` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `code` varchar(10) NOT NULL,
  `expiration_date` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UQ_TwoFactorVerifications_User_Id` (`user_id`),
  UNIQUE KEY `UQ_TwoFactorVerifications_Code` (`code`),
  CONSTRAINT `twofactorverifications_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=46 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `twofactorverifications`
--

LOCK TABLES `twofactorverifications` WRITE;
/*!40000 ALTER TABLE `twofactorverifications` DISABLE KEYS */;
INSERT INTO `twofactorverifications` VALUES (33,8,'PAILUXW','2026-05-05 19:31:36'),(45,4,'QBUQXSM','2026-05-24 00:27:51');
/*!40000 ALTER TABLE `twofactorverifications` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `userevents`
--

DROP TABLE IF EXISTS `userevents`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `userevents` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `event_id` bigint unsigned NOT NULL,
  `device` varchar(100) DEFAULT NULL,
  `ip_address` varchar(100) DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `user_id` (`user_id`),
  KEY `event_id` (`event_id`),
  CONSTRAINT `userevents_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `userevents_ibfk_2` FOREIGN KEY (`event_id`) REFERENCES `events` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=276 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `userevents`
--

LOCK TABLES `userevents` WRITE;
/*!40000 ALTER TABLE `userevents` DISABLE KEYS */;
INSERT INTO `userevents` VALUES (127,7,4,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 17:04:38'),(128,7,8,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 17:04:56'),(129,7,6,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 17:05:01'),(130,7,7,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 17:05:04'),(131,7,7,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 17:05:06'),(132,7,7,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 17:05:08'),(133,7,7,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 17:05:11'),(134,7,9,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 17:05:14'),(135,7,9,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 17:05:16'),(136,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 17:31:20'),(137,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 17:31:21'),(138,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 22:17:42'),(139,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 22:17:43'),(140,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 22:48:20'),(141,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 22:48:21'),(142,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 23:42:07'),(143,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-09 23:42:08'),(144,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 00:14:15'),(145,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 00:14:16'),(146,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 00:48:26'),(147,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 00:48:27'),(148,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 01:20:28'),(149,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 01:20:29'),(150,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 01:50:55'),(151,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 01:50:56'),(152,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 14:55:57'),(153,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 14:55:59'),(154,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 15:26:30'),(155,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 15:26:32'),(156,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 16:35:10'),(157,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 16:35:12'),(158,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 17:05:19'),(159,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-10 17:05:20'),(160,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-11 14:57:57'),(161,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-11 14:57:59'),(162,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-13 18:10:10'),(163,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-13 18:10:11'),(164,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-13 18:40:14'),(165,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-13 18:40:15'),(166,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-13 19:44:39'),(167,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-13 19:44:40'),(168,7,1,'Cloud - PostmanRuntime - Postman Runtime','0:0:0:0:0:0:0:1','2026-05-14 17:52:14'),(169,7,2,'Cloud - PostmanRuntime - Postman Runtime','0:0:0:0:0:0:0:1','2026-05-14 17:52:15'),(170,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-14 17:55:42'),(171,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-14 17:55:43'),(172,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-14 18:36:28'),(173,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-14 18:36:29'),(174,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-14 19:06:53'),(175,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-14 19:06:53'),(176,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-14 19:39:12'),(177,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-14 19:39:13'),(178,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-14 20:02:01'),(179,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-14 20:02:02'),(180,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 01:01:14'),(181,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 01:01:15'),(182,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 01:32:40'),(183,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 01:32:41'),(184,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 02:14:12'),(185,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 02:14:13'),(186,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 02:49:12'),(187,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 02:49:13'),(188,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 03:00:07'),(189,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 03:00:08'),(190,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 03:33:02'),(191,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 03:33:03'),(192,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 14:35:55'),(193,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 14:35:56'),(194,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 18:11:42'),(195,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 18:11:43'),(196,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 18:42:38'),(197,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 18:42:39'),(198,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 19:19:35'),(199,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 19:19:36'),(200,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 20:25:08'),(201,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 20:25:10'),(202,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 21:02:51'),(203,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 21:02:52'),(204,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 23:30:02'),(205,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-16 23:30:03'),(206,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-17 16:00:35'),(207,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-17 16:00:37'),(208,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-17 17:40:36'),(209,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-17 17:40:37'),(210,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-17 18:17:03'),(211,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-17 18:17:04'),(212,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-17 19:03:30'),(213,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-17 19:03:32'),(214,7,1,'Cloud - PostmanRuntime - Postman Runtime','0:0:0:0:0:0:0:1','2026-05-17 19:44:10'),(215,7,2,'Cloud - PostmanRuntime - Postman Runtime','0:0:0:0:0:0:0:1','2026-05-17 19:44:11'),(216,7,1,'Cloud - PostmanRuntime - Postman Runtime','0:0:0:0:0:0:0:1','2026-05-18 19:36:03'),(217,7,2,'Cloud - PostmanRuntime - Postman Runtime','0:0:0:0:0:0:0:1','2026-05-18 19:36:04'),(218,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-18 20:18:42'),(219,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-18 20:18:43'),(220,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-18 20:48:52'),(221,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-18 20:48:53'),(222,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-18 21:22:26'),(223,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-18 21:22:27'),(224,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-19 17:29:07'),(225,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-19 17:29:08'),(226,9,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-19 22:25:03'),(227,9,3,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-19 22:25:04'),(228,1,1,'Windows NT - Edge - Desktop','0:0:0:0:0:0:0:1','2026-05-20 17:11:33'),(229,1,2,'Windows NT - Edge - Desktop','0:0:0:0:0:0:0:1','2026-05-20 17:11:45'),(230,7,1,'Cloud - PostmanRuntime - Postman Runtime','0:0:0:0:0:0:0:1','2026-05-20 17:18:04'),(231,7,2,'Cloud - PostmanRuntime - Postman Runtime','0:0:0:0:0:0:0:1','2026-05-20 17:18:05'),(232,7,1,'Cloud - PostmanRuntime - Postman Runtime','0:0:0:0:0:0:0:1','2026-05-20 17:23:02'),(233,7,2,'Cloud - PostmanRuntime - Postman Runtime','0:0:0:0:0:0:0:1','2026-05-20 17:23:04'),(234,12,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 14:51:44'),(235,12,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 14:51:46'),(236,12,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 17:09:45'),(237,12,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 17:09:46'),(238,14,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 18:05:59'),(239,14,3,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 18:06:00'),(240,15,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 18:06:50'),(241,15,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 18:06:51'),(242,16,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 18:25:30'),(243,16,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 18:25:31'),(244,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 18:26:46'),(245,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 18:26:48'),(246,4,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 18:27:19'),(247,4,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-22 18:27:33'),(248,4,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 00:27:50'),(249,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 00:27:58'),(250,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 00:27:59'),(251,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 13:18:18'),(252,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 13:18:19'),(253,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 16:12:43'),(254,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 16:12:44'),(255,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 16:18:19'),(256,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 16:18:20'),(257,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 16:48:28'),(258,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 16:48:29'),(259,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 17:26:37'),(260,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 17:26:38'),(261,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 17:57:04'),(262,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 17:57:05'),(263,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 22:03:42'),(264,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 22:03:43'),(265,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 22:39:52'),(266,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-23 22:39:53'),(267,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-24 00:26:32'),(268,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-24 00:26:33'),(269,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-24 00:40:38'),(270,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-24 00:40:40'),(271,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-24 01:04:23'),(272,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-24 01:04:24'),(273,7,4,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-24 01:09:36'),(274,7,1,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-24 17:07:08'),(275,7,2,'Windows NT - Chrome - Desktop','0:0:0:0:0:0:0:1','2026-05-24 17:07:09');
/*!40000 ALTER TABLE `userevents` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `userroles`
--

DROP TABLE IF EXISTS `userroles`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `userroles` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `role_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UQ_UserRoles_User_Id` (`user_id`),
  KEY `role_id` (`role_id`),
  CONSTRAINT `userroles_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `userroles_ibfk_2` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `userroles`
--

LOCK TABLES `userroles` WRITE;
/*!40000 ALTER TABLE `userroles` DISABLE KEYS */;
INSERT INTO `userroles` VALUES (1,4,1),(2,5,1),(3,6,1),(4,3,1),(5,2,1),(6,1,1),(7,7,4),(8,8,1),(9,9,1),(10,10,1),(11,11,1),(12,12,1),(13,13,1),(14,14,1),(15,15,1),(16,16,1);
/*!40000 ALTER TABLE `userroles` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `users`
--

DROP TABLE IF EXISTS `users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `first_name` varchar(50) NOT NULL,
  `last_name` varchar(50) NOT NULL,
  `email` varchar(100) NOT NULL,
  `password` varchar(255) DEFAULT NULL,
  `address` varchar(255) DEFAULT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `title` varchar(50) DEFAULT NULL,
  `bio` varchar(255) DEFAULT NULL,
  `enabled` tinyint(1) DEFAULT '0',
  `non_locked` tinyint(1) DEFAULT '1',
  `using_mfa` tinyint(1) DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `image_url` varchar(255) DEFAULT 'https://cdn-icons-png.flaticon.com/512/149/149071.png',
  `password_changed_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UQ_Users_Email` (`email`)
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `users`
--

LOCK TABLES `users` WRITE;
/*!40000 ALTER TABLE `users` DISABLE KEYS */;
INSERT INTO `users` VALUES (1,'bob','oliver','bob@yopmail.com','$2a$12$Xp79K5ESR2tXUldKAa3lMeF5oqGNwOfHmqbZJkpludLaqqZQ3BKX2',NULL,'8084824518',NULL,NULL,1,1,1,'2026-04-17 01:09:43','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL),(2,'bob','oliver','bobby@yopmail.com','$2a$12$hqej1PC2TLvyb22pnK6tkONuI1LEtxBg0PG..Z2o0EvEX8DzQe6T2',NULL,NULL,NULL,NULL,1,1,0,'2026-04-17 01:09:59','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL),(3,'bob','oliver','bobbylon@yopmail.com','$2a$12$rS1u5z5HkJUn1ElUng0Ume168M5xpoGHD9RCHH5sWoxzEp7Ut2K3O',NULL,NULL,NULL,NULL,1,1,0,'2026-04-17 01:41:33','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL),(4,'bob','oliver','bobbylon1@yopmail.com','$2a$12$We2bBdrFYajDleeq6Ye6SOXO34JOgQBI60gpweD5QFWhDqRJMNAbG',NULL,'8084824518',NULL,NULL,1,1,1,'2026-04-17 16:22:52','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL),(5,'bob','oliver','bobbylon12@yopmail.com','$2a$12$RQTsrqNk5WoRXC1SrGH5xutpKZceD5.U8HXfjZ6hGVpF/9OhCAbjC',NULL,'8084824518',NULL,NULL,1,1,1,'2026-04-17 16:31:08','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL),(6,'bob','oliver','bobbylon123@yopmail.com','$2a$12$f1VgFa8LZ54L0GIZJrFLG.gdLDfacduPtzfdNTofe9Z/IDirvDl6O',NULL,'8084824518',NULL,NULL,1,1,1,'2026-04-18 16:33:13','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL),(7,'bobbylon','oliver','bobnomfa@yopmail.com','$2a$12$Jfjkn7e2JiWRIjhpSQDm0uDI15NLy2Lj6NDIsWcLA/5KUqZe8segy','123 Any Street','1234567890','Boss','A Cool guy',1,1,0,'2026-04-25 09:45:54','http://localhost:8080/user/image/bobnomfa@yopmail.com.png','2026-05-22 18:26:38'),(8,'bob1','oliver1','bobnewaccount@yopmail.com','$2a$12$uKF2pfd0W0mOaHvJwwa24ONsGQ4xNZaMUG6t0ozLQ.YYb/Um.OBkG',NULL,NULL,NULL,NULL,1,1,1,'2026-05-03 10:39:59','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL),(9,'Robert','Oliver Jr','bobwithsomemfa@yopmail.com','$2a$12$EQob6OOuOpM0k3RKxCaHN.F3.PEcOGYce2R4tYAX9B4HZsibxc7rG',NULL,NULL,NULL,NULL,0,1,0,'2026-05-19 19:14:01','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL),(10,'test','bob','bobtestsomemfa@tupmail.com','$2a$12$jt64.7QCKyX7YAK329YDz.drUB/F06KSd611ko1a9tEAeeoUZxfpO',NULL,NULL,NULL,NULL,1,1,0,'2026-05-19 19:16:20','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL),(11,'test','tests','12345@yopmail.com','$2a$12$yYGCgha5LwhqL23.VOOROukV3h3BZnq4oa3VXOegGcpcD5x2691iy',NULL,NULL,NULL,NULL,1,1,0,'2026-05-19 19:18:10','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL),(12,'bob','testing','bobsomemfa@yopmail.com','$2a$12$QE21JzleaqYCH8Z0IMe.dOZRgEaHJz/.00fXNqRtZsv5DuaOCKraO',NULL,NULL,NULL,NULL,1,1,0,'2026-05-22 11:23:15','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL),(13,'bob','testing','bobemail@yopmail.com','$2a$12$tfmSrk6D7VW6naVgdx0diO9lxeG7zaDf7Ap/o9M/lCzig8lr3gV7O',NULL,NULL,NULL,NULL,0,1,0,'2026-05-22 17:10:08','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL),(14,'bob','test','newbobemail@yopmail.com','$2a$12$hbDBzl4xkfolcKAKpQ91tuhTyZOVHB/XAZ2uYeDj2DCyIRBh1qRCS',NULL,NULL,NULL,NULL,0,1,0,'2026-05-22 17:50:22','https://cdn-icons-png.flaticon.com/512/149/149071.png','2026-05-22 18:05:50'),(15,'test','bob','newbobemail1@yopmail.com','$2a$12$rxezPVwcf3EtXcUIJ5foh.O2RyhU.bhgRem77dBB7gpjznBfttRRC',NULL,NULL,NULL,NULL,1,1,0,'2026-05-22 17:55:48','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL),(16,'test','newbobg','newbobemail2@yopmail.com','$2a$12$/vqjeq/wpm8QPGoMdY9ecOs68ReFpcskvwJVh7eEClauwACap215a',NULL,NULL,NULL,NULL,1,1,0,'2026-05-22 18:24:58','https://cdn-icons-png.flaticon.com/512/149/149071.png',NULL);
/*!40000 ALTER TABLE `users` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-05-25 17:32:44
