package eu.transparency.lobbycal.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import eu.transparency.lobbycal.aop.audit.AuditEventPublisher;
import eu.transparency.lobbycal.domain.Alias;
import eu.transparency.lobbycal.domain.Meeting;
import eu.transparency.lobbycal.domain.Partner;
import eu.transparency.lobbycal.domain.Submitter;
import eu.transparency.lobbycal.domain.Tag;
import eu.transparency.lobbycal.domain.User;
import eu.transparency.lobbycal.domain.util.JSR310DateConverters;
import eu.transparency.lobbycal.repository.AliasRepository;
import eu.transparency.lobbycal.repository.MeetingRepository;
import eu.transparency.lobbycal.repository.PartnerRepository;
import eu.transparency.lobbycal.repository.SubmitterRepository;
import eu.transparency.lobbycal.repository.TagRepository;
import eu.transparency.lobbycal.repository.UserRepository;
import eu.transparency.lobbycal.repository.search.MeetingSearchRepository;
import eu.transparency.lobbycal.security.SecurityUtils;
import jodd.io.FileUtil;
import jodd.io.NetUtil;
import jodd.jerry.Jerry;
import jodd.jerry.JerryFunction;
import jodd.mail.MailAddress;
import jodd.util.SystemUtil;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;

/**
 * Handles parsing of incoming emails
 * 
 * @author lobbycal
 *
 */
@Service
@Transactional
public class MeetingService {

	private static final Pattern TAG_PATTERN = Pattern.compile("(?:^|\\s|[\\p{Punct}&&[^/]])(#[\\p{L}0-9-_]+)");


	private final Logger log = LoggerFactory.getLogger(MeetingService.class);


	static String name = "";


	@Inject
	private AuditEventPublisher auditPublisher;


	@Inject
	private MeetingRepository meetingRepository;


	@Inject
	private MeetingSearchRepository meetingSearchRepository;


	@Inject
	private UserRepository userRepository;


	@Inject
	private PartnerRepository partnerRepository;


	@Inject
	private TagRepository tagRepository;


	@Inject
	private SubmitterRepository submitterRepository;


	@Inject
	private AliasRepository aliasRepository;

	public Optional<Meeting> processRequest(MailAddress[] mailTOsAndCCs, MailAddress fromEmail, Calendar calendar,
			String emailSubject, String encoding) {

		try {
			log.debug("processing  meeting {} for to/cc {} and submitter {} for event {}",
					calendar.getProperty("METHOD").getValue(), mailTOsAndCCs, fromEmail.getEmail(),
					calendar.getComponent("VEVENT").getProperty("DTSTART").toString().trim());

			// let's examine the senders

			// 1: CC must match one of user.aliases

			// 2: FROM must match either user.email or one of
			// user.submitters

			// 1: did they use a valid alias?
			User userWithAlias = null;
			String ali = null;
			for (MailAddress cc : mailTOsAndCCs) {
				ali = cc.getEmail().subSequence(0, cc.getEmail().indexOf("@")).toString();
				log.debug("Alias to check : " + ali);
				Alias aliasOfUser = aliasRepository.findOneByAlias(ali);
				if (aliasOfUser != null) {
					userWithAlias = aliasOfUser.getUser();
					log.debug("Alias belongs to  " + ali + " : User " + userWithAlias.getLogin() + " with email "
							+ userWithAlias.getEmail());
					break;
				}
			}
			if (userWithAlias == null) {
				log.debug("Meeting not saved. Sent from " + fromEmail + " to an alias not corresponding to the sender:"
						+ ali);
				auditThis(
						new String[] { "message=Meeting not saved. Sent from " + fromEmail
								+ " to an alias not corresponding to the sender:  " + ali },
						AuditEventPublisher.TYPE_MAIL);
				return Optional.empty();
			}

			// 2: check if there is a user or submitter whose email matches
			// FROM:
			Optional<User> user = userRepository.findOneByEmail(userWithAlias.getEmail());
			String submitterInFact = null;
			if (user.isPresent()) {
				log.debug("" + user.get().getEmail());
				// .... or whose allowed submitter emails matches FROM:
				for (Submitter submitter : submitterRepository.findAllByUserId(user.get().getId())) {
					log.debug("Submitter email: " + submitter.getEmail());
					if (submitter.getEmail().compareToIgnoreCase(fromEmail.getEmail()) == 0) {
						// submitter is still active?
						log.info("First submitter " + submitter.getId() + " active ? " + submitter.getActive()
								+ "   match in lobbycal for user login " + submitter.getUser().getLogin()
								+ " and alias " + ali);
						if (submitter.getActive()) {
							user = Optional.of(submitter.getUser());
							submitterInFact = submitter.getEmail();
							log.debug("Submitter active " + submitterInFact);
							break;
						} else {
							log.warn("Submitter not active " + submitterInFact);
							auditThis(new String[] {
									"message=Submitter not active. " + submitterInFact + " " + submitter.getEmail() },
									AuditEventPublisher.TYPE_MAIL);
							return Optional.empty();
						}
					} else {
						log.debug("mail sent from " + fromEmail);
						log.debug("lobbycal user {} himself is sender with email {} ", user.get().getLogin(),
								user.get().getEmail());

						submitterInFact = fromEmail.getEmail();
					}
					log.debug("");
				}
				log.debug("");

			} else {
				log.debug("User {} does not exist ", fromEmail);
				// submitterInFact = fromEmail.getEmail();
				auditThis(new String[] {
						"message=Meeting Processing not successfull. Unauthorized attempt to create a meeting entry sent from "
								+ fromEmail.getEmail() },
						AuditEventPublisher.TYPE_MAIL);

				return Optional.empty();
			}

			// user is there or we return empty optional
			// removed as requested in #92
			// if
			// (user.get().getEmail().toLowerCase().compareTo(fromEmail.getEmail().toLowerCase())
			// != 0) {
			// log.error("AUDIT EVENT: unauthorized attempt to create a meeting
			// entry for "
			// + user.get().getEmail()
			// + " sent from "
			// + fromEmail.getEmail());
			// auditThis(
			// new String[] {
			// "message=Meeting Processing not successfull.Unauthorized attempt
			// to create a meeting entry for "
			// + user.get().getEmail()
			// + " sent from "
			// + fromEmail.getEmail() },
			// AuditEventPublisher.TYPE_MAIL);
			//
			// return Optional.empty();
			// }

			if (!user.isPresent()) {
				log.error("d'ough!");
				log.debug("");
				auditThis(new String[] { "message=Meeting Processing not successfull. 	No corresponding user  " },
						AuditEventPublisher.TYPE_MAIL);

				return Optional.empty();
			} else {

				log.debug("Processing calendar");
				// let's handle the calendar
				Optional<Meeting> stored = meetingRepository
						.findOneByUid(calendar.getComponent("VEVENT").getProperty("UID").getValue());
				// apparently UID are not always identical, need to repeat
				// delete
				// block later
				if (stored.isPresent()) {
					log.debug("");
					// DELETE
					if (calendar.getProperty("METHOD").getValue().compareTo("CANCEL") == 0) {
						meetingRepository.delete(stored.get());
						meetingSearchRepository.delete(stored.get());
						log.debug("Meeting Deleted " + stored.get().getUid());

						auditThis(
								new String[] { "message= Meeting deleted  " + stored.get().getTitle() + "  "
										+ stored.get().getUser().getLogin() + " " + stored.get().getId().toString() },
								null);
						return Optional.empty();
					}
					// UPDATE
					if (calendar.getProperty("METHOD").getValue().compareTo("REQUEST") == 0) {
						log.debug("" + submitterInFact);
						VEvent vevent = (VEvent) calendar.getComponent("VEVENT");

						log.info(vevent.getSummary().getValue());
						stored.get().setUser(user.get());
						stored.get().setUid(vevent.getUid().getValue());
						stored.get().setSubmitter(submitterInFact);
						stored.get().setAliasUsed(ali);

						stored.get().setStartDate(JSR310DateConverters.DateToZonedDateTimeConverter.INSTANCE
								.convert(vevent.getStartDate().getDate()));
						stored.get().setEndDate(JSR310DateConverters.DateToZonedDateTimeConverter.INSTANCE
								.convert(vevent.getEndDate().getDate()));

						String value = new String(vevent.getSummary().getValue());

						parseSubject(value, stored.get());
						meetingRepository.save(stored.get());
						meetingSearchRepository.save(stored.get());
						auditThis(
								new String[] {
										"message=Meeting updated, ID: " + "  " + stored.get().getId().toString() },
								null);

						return Optional.of(stored.get());
					}

				} else {

					// we need to have the CANCEL Block here again, as horde
					// adds 2
					// cancellation .ics files to the cancelation email
					// DELETE
					if (calendar.getProperty("METHOD").getValue().compareTo("CANCEL") == 0) {
						log.debug("deleted event already");
						auditThis(new String[] { "message=Meeting was already deleted" }, null);

						return Optional.empty();
					}
					// CREATE

					VEvent vevent = (VEvent) calendar.getComponent("VEVENT");
					log.info(vevent.getSummary().getValue());
					Meeting newMeeting = new Meeting();
					newMeeting.setUser(user.get());
					newMeeting.setUid(vevent.getUid().getValue());
					newMeeting.setSubmitter(submitterInFact);
					newMeeting.setAliasUsed(ali);
					newMeeting.setStartDate(JSR310DateConverters.DateToZonedDateTimeConverter.INSTANCE
							.convert(vevent.getStartDate().getDate()));
					newMeeting.setEndDate(JSR310DateConverters.DateToZonedDateTimeConverter.INSTANCE
							.convert(vevent.getEndDate().getDate()));
					String value = vevent.getSummary().getValue();

					parseSubject(value, newMeeting);
					meetingRepository.save(newMeeting);
					meetingSearchRepository.save(newMeeting);
					log.debug("");
					auditThis(new String[] { "message=Created meeting " + newMeeting.getId().toString() + "  "
							+ newMeeting.getTitle() + "  " + newMeeting.getUser().getLogin() }, null);
					return Optional.of(newMeeting);
				}
				log.debug("");
			}
			log.debug("");
		} catch (Exception uec) {
			log.error("" + uec.getMessage());
			uec.printStackTrace();
			return Optional.empty();
		}
		return Optional.empty();
	}

	/**
	 * 
	 * @param emailSubject
	 * @param meeting
	 *            Update 1.9.2015: parses twitter like tags and adds them to the
	 *            meeting
	 * @return the meeting with partner, partnerid and subject line set
	 *         according to specification
	 */
	private Meeting parseSubject(String emailSubject, Meeting meeting) {

		// Parse data from event title: Partner, title, transparency register ID
		// – ignoring everything that
		// follows a special “EOF” character: '*'
		// Example title:
		// GESAC: Exceptions and limitations 30161717506-48 * +49 123 456
		// [Partner]: [Title] [TransparencyRegisterID] * [comment]
		// [Partner]: [Title] [TransparencyRegisterID] [#tag1 ][#tag2] *
		// [comment]

		String title = "";
		String transparencyRegisterIDdRegex = null;//"(\\d{9}|\\d{10}|\\d{11}|\\d{12})-\\d{2}|noid|NOID|noID|noId|NoId|NoID";
		transparencyRegisterIDdRegex = "(\\d{9}|\\d{10}|\\d{11}|\\d{12})-\\d{2}[;,]*+|noid[;,]*+|NOID[;,]*+|noID[;,]*+|noId[;,]*+|NoId[;,]*+|NoID[;,]*+";

		if (emailSubject.contains("*")) {
			emailSubject = emailSubject.substring(0, emailSubject.indexOf("*"));
		}

		// TAGS

		Matcher tagMatcher = TAG_PATTERN.matcher(emailSubject);
		Set<Tag> tags = new HashSet<Tag>();
		while (tagMatcher.find()) {
			Tag aTag = new Tag();
			String current = (tagMatcher.group());
			current = current.trim().toLowerCase();
			aTag.seti18nKey(current);
			aTag.setEn(current);
			aTag.setId(current.hashCode() * 1l);
			log.debug("adding tag " + aTag.geti18nKey() + ": " + tags.add(tagRepository.saveAndFlush(aTag)));

		}
		meeting.setTags(tags);

		tagMatcher = TAG_PATTERN.matcher(emailSubject);
		while (tagMatcher.find()) {
			String current = (tagMatcher.group());
			emailSubject = emailSubject.replace(current, "");

		}

		// PARTNERS

		ArrayList<String> registerIDs = new ArrayList<String>();
		ArrayList<String> partnerNames = new ArrayList<String>();
		String givenPartnerName = "";
		// if colon in subject line

		title = emailSubject.substring(emailSubject.lastIndexOf(":") == -1 ? 0 : emailSubject.lastIndexOf(":"))
				.replace(":", "").trim();
		log.info(title);
		Matcher m = Pattern.compile(transparencyRegisterIDdRegex).matcher(emailSubject);
		while (m.find()) {
			String c = m.group();
			log.info(c);
			registerIDs.add(c.replaceAll(",", "").replaceAll(";",""));
			title = title.replace(c, "");
		}
		// strip out register ids

		boolean existsPartnerNameFromSubject = false;
		if (emailSubject.contains(":")) {
			givenPartnerName = emailSubject.substring(0, emailSubject.lastIndexOf(":")).trim();
			// remove re: reply to: etc
			givenPartnerName = givenPartnerName.substring(givenPartnerName.lastIndexOf(":") + 1).trim();

			for (String id : registerIDs) {
				log.info(id);
				givenPartnerName = givenPartnerName.replace(id.trim(), "");
				title = title.replace(id.trim(), "").replace("noId", "").replace("NOID", "").replace("noid", "")
						.replace("noID", "").replace("Noid", "").replace("NoId", "");
			}
			log.info("Given partner name\t|" + givenPartnerName + "|");
			// tokenize
			StringTokenizer stok = new StringTokenizer(givenPartnerName, ";");
			while (stok.hasMoreTokens()) {
				String subPartnerName = stok.nextToken().trim();
				log.info(subPartnerName);
				partnerNames.add(subPartnerName);
			}
			existsPartnerNameFromSubject = true;

		}
		Set<Partner> partners = new HashSet<Partner>();

		log.debug("create new partners");
		// no colon p name > handle as usual
		// colon p name > count tokens and noIDs > assign parsed trid in order
		// of appearance
		if (!existsPartnerNameFromSubject) {
			partnerNames = registerIDs;
		}
		for (int i = 0; i < partnerNames.size(); i++) {
			Optional<Partner> p = null;
			Partner np = new Partner();
			p = Optional.of(np);

			if (existsPartnerNameFromSubject) {
				log.info(i + "");
				String pName = partnerNames.get(i).trim();
				String regid = i < registerIDs.size() ? registerIDs.get(i) : "noid";
				if (pName.length() > 255) {
					pName = pName.substring(0, 254);
				}
				p.get().setName(pName);
				p.get().setTransparencyRegisterID(regid.toLowerCase().compareTo("noid")==0?"":regid);
			} else {
				// p.get().setName(getPartnerNameFromTransparencyRegister(string));
				p.get().setTransparencyRegisterID(registerIDs.get(i));
			}
			log.info("Adding partner " + p.get().toString());
			partners.add(partnerRepository.saveAndFlush(p.get()));
		}

		meeting.setPartners(partners);

		meeting.setTitle(title.replace("  ", " "));

		return meeting;
	}

	private String getPartnerNameFromTransparencyRegister(String string) {

		try {
			File file = new File(SystemUtil.tempDir(), "partner.html");
			NetUtil.downloadFile(
					"http://ec.europa.eu/transparencyregister/public/consultation/displaylobbyist.do?id=" + string,
					file);
			name = "";
			// create Jerry, i.e. document context
			Jerry doc = Jerry.jerry(FileUtil.readString(file));
			// parse
			doc.$("div.panel-body h4").each(new JerryFunction() {

				public boolean onNode(Jerry $this, int index) {

					name = $this.$("b").text();
					return true;
				}
			});

		} catch (IOException e) {
			e.printStackTrace();
		}
		log.info(name);
		return name;
	}

	void auditThis(String[] msg, String type) {

		if (type == null) {
			type = AuditEventPublisher.TYPE_MEETING;
		}

		AuditEvent event = new AuditEvent(
				SecurityUtils.getCurrentUserLogin() == null ? "system" : SecurityUtils.getCurrentUserLogin(), type,
				msg);
		auditPublisher.publish(event);

	}

}
