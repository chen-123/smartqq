package com.scienjus.smartqq.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpContentResponse;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.scienjus.smartqq.constant.ApiURL;
import com.scienjus.smartqq.exception.ApiException;
import com.scienjus.smartqq.exception.RequestAbortException;
import com.scienjus.smartqq.exception.ResponseException;
import com.scienjus.smartqq.json.GsonUtil;
import com.scienjus.smartqq.listener.ExceptionThreadType;
import com.scienjus.smartqq.listener.SmartqqListener;
import com.scienjus.smartqq.model.Category;
import com.scienjus.smartqq.model.Discuss;
import com.scienjus.smartqq.model.DiscussInfo;
import com.scienjus.smartqq.model.DiscussMessage;
import com.scienjus.smartqq.model.DiscussUser;
import com.scienjus.smartqq.model.Font;
import com.scienjus.smartqq.model.Friend;
import com.scienjus.smartqq.model.FriendStatus;
import com.scienjus.smartqq.model.Group;
import com.scienjus.smartqq.model.GroupInfo;
import com.scienjus.smartqq.model.GroupMessage;
import com.scienjus.smartqq.model.GroupUser;
import com.scienjus.smartqq.model.Message;
import com.scienjus.smartqq.model.MessageContentElement;
import com.scienjus.smartqq.model.MessageContentElementUtil;
import com.scienjus.smartqq.model.Recent;
import com.scienjus.smartqq.model.UserInfo;
import com.scienjus.smartqq.model.UserStatus;
import com.scienjus.smartqq.model.WithUserId;

/**
 * Api客户端.
 *
 * @author ScienJus
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @author <a href="mailto:xianguang.zhou@outlook.com">Xianguang Zhou</a>
 * @since 2015/12/18.
 */
public class SmartQQClient implements Closeable, WithUserId {

	// 日志
	private static final Logger LOGGER = LoggerFactory.getLogger(SmartQQClient.class);

	// 消息发送失败重发次数
	private static final long RETRY_TIMES = 5;

	// 消息id，这个好像可以随便设置
	private long MESSAGE_ID = 43690001;

	// 客户端id，固定的
	private static final long Client_ID = 53999199;

	// HTTP客户端
	private HttpClient httpClient;

	// 鉴权参数
	private String ptwebqq;

	private String vfwebqq;

	private long uin;

	private String psessionid;

	// 线程开关
	private volatile boolean polling = true;

	private SmartqqListener listener;

	private Thread pollThread;

	private final Object pollWaitObject = new Object();

	private Request pollRequest = null;

	// self info
	private String selfUserStatus;

	private Random faceDomainSuffixRandom = new Random();

	/**
	 * 是否使用HTTPS加密聊天内容
	 */
	private boolean httpsChatMessage = false;

	/**
	 * 创建一个Smart QQ的客户端
	 * 
	 * @param smartqqListener
	 *            Smart QQ的监听器
	 */
	public SmartQQClient(SmartqqListener smartqqListener) {
		if (null == smartqqListener) {
			throw new NullPointerException("SmartqqListener can't be null.");
		}
		this.listener = new SmartqqListenerDecorator(smartqqListener);

		this.httpClient = new HttpClient(new SslContextFactory());
		this.httpClient.setUserAgentField(new HttpField(HttpHeader.USER_AGENT, ApiURL.USER_AGENT));
		this.httpClient.setFollowRedirects(true);
	}

	/**
	 * 设置是否使用HTTPS加密聊天内容
	 * 
	 * @param httpsChatMessage
	 *            是否使用HTTPS加密聊天内容
	 */
	public void setHttpsChatMessage(boolean httpsChatMessage) {
		this.httpsChatMessage = httpsChatMessage;
	}

	/**
	 * 
	 * @throws Exception
	 *             如果httpClient启动失败，或者初始请求失败
	 */
	public void start() throws Exception {
		this.httpClient.start();
		httpClient.newRequest("http://w.qq.com/").method(HttpMethod.GET).agent(ApiURL.USER_AGENT)
				.header("Upgrade-Insecure-Requests", "1").send();
	}

	/**
	 * 获取二维码
	 * 
	 * @return 二维码图片的内容
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	// 登录流程1：获取二维码
	public byte[] getQRCode() throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取二维码");

		byte[] imageBytes = httpClient.newRequest(ApiURL.GET_QR_CODE.getUrl()).timeout(10, TimeUnit.SECONDS)
				.method(HttpMethod.GET).agent(ApiURL.USER_AGENT).send().getContent();

		LOGGER.debug("二维码已获取");

		return imageBytes;
	}

	/**
	 * 登录，阻塞直到确认二维码认证成功
	 * 
	 * @return 二维码是否失效。true表示二维码已失效，登录失败；false表示登录成功。
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 * @throws URISyntaxException
	 *             if a string could not be parsed as a URI reference
	 */
	public boolean login() throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
		String url = verifyQRCode();
		if (url != null) {
			getPtwebqq(url);
			getVfwebqq();
			getUinAndPsessionid();
			avoidRetcode103();
			getFriendStatus();
			startPolling();
			return false;
		} else {
			return true;
		}
	}

	private void startPolling() {
		pollThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (polling) {
					try {
						synchronized (pollWaitObject) {
							if (polling) {
								pollMessage(listener);
								pollWaitObject.wait();
							}
						}
					} catch (Exception ex) {
						LOGGER.error(ex.getMessage(), ex);
						listener.onException(ex, ExceptionThreadType.POLL_THREAD);
					}
				}
			}
		});
		pollThread.start();
	}

	// 登录流程2：校验二维码
	private String verifyQRCode() throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("等待扫描二维码");

		// 阻塞直到确认二维码认证成功
		while (true) {
			sleep(1);
			ContentResponse response = get(ApiURL.VERIFY_QR_CODE);
			String result = response.getContentAsString();
			if (result.contains("成功")) {
				for (String content : result.split("','")) {
					if (content.startsWith("http")) {
						LOGGER.info("正在登录，请稍后");

						return content;
					}
				}
			} else if (result.contains("已失效")) {
				LOGGER.info("二维码已失效");
				return null;
			}
		}
	}

	// 登录流程3：获取ptwebqq
	private void getPtwebqq(String url) throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取ptwebqq");

		ContentResponse response = get(ApiURL.GET_PTWEBQQ, url);
		List<HttpCookie> httpCookieList = httpClient.getCookieStore().get(response.getRequest().getURI());
		for (HttpCookie httpCookie : httpCookieList) {
			if ("ptwebqq".equals(httpCookie.getName())) {
				this.ptwebqq = httpCookie.getValue();
				break;
			}
		}
	}

	// 登录流程4：获取vfwebqq
	private void getVfwebqq() throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取vfwebqq");

		ContentResponse response = get(ApiURL.GET_VFWEBQQ, ptwebqq);
		this.vfwebqq = getJsonObjectResult(response).get("vfwebqq").getAsString();
	}

	// 登录流程5：获取uin和psessionid
	private void getUinAndPsessionid()
			throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
		LOGGER.debug("开始获取uin和psessionid");

		{
			CookieStore cookieStore = httpClient.getCookieStore();
			cookieStore.add(new URI("qq.com"), new HttpCookie("pgv_info", "ssid=s" + RandomUtil.numberString(10)));
			cookieStore.add(new URI("qq.com"), new HttpCookie("pgv_pvid", RandomUtil.numberString(10)));
			httpClient
					.newRequest(
							"https://ui.ptlogin2.qq.com/cgi-bin/login?daid=164&target=self&style=16&mibao_css=m_webqq&appid=501004106&enable_qlogin=0&no_verifyimg=1&s_url=http%3A%2F%2Fw.qq.com%2Fproxy.html&f_url=loginerroralert&strong_login=1&login_state=10&t=20131024001")
					.method(HttpMethod.GET).agent(ApiURL.USER_AGENT).header("Referer", "http://w.qq.com/")
					.header("Upgrade-Insecure-Requests", "1").send();
		}

		httpClient.newRequest("http://d1.web2.qq.com/proxy.html?v=20151105001&callback=1&id=2").method(HttpMethod.GET)
				.agent(ApiURL.USER_AGENT).header("Referer", "http://w.qq.com/").header("Upgrade-Insecure-Requests", "1")
				.send();

		JsonObject r = new JsonObject();
		r.addProperty("ptwebqq", ptwebqq);
		r.addProperty("clientid", Client_ID);
		r.addProperty("psessionid", "");
		r.addProperty("status", "online");

		ContentResponse response = post(ApiURL.GET_UIN_AND_PSESSIONID, r);
		JsonObject result = getJsonObjectResult(response);
		this.psessionid = result.get("psessionid").getAsString();
		this.uin = result.get("uin").getAsLong();
		this.selfUserStatus = result.get("status").getAsString();
	}
	
	private void avoidRetcode103() throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("Send request to avoid receiving return code 103.");
		
		getResponseJson(get(ApiURL.AVOID_RETCODE_103, vfwebqq, Client_ID, psessionid));
	}

	/**
	 * 获取群列表
	 *
	 * @return 群列表
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public List<Group> getGroupList() throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取群列表");

		JsonObject r = new JsonObject();
		r.addProperty("vfwebqq", vfwebqq);
		r.addProperty("hash", hash());

		ContentResponse response = post(ApiURL.GET_GROUP_LIST, r);
		JsonObject result = getJsonObjectResult(response);
		return GsonUtil.gson.fromJson(result.get("gnamelist"), new TypeToken<List<Group>>() {
		}.getType());
	}

	/**
	 * 拉取消息
	 *
	 * @param callback
	 *            获取消息后的回调
	 */
	private void pollMessage(final SmartqqListener callback)
			throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始接收消息");

		JsonObject r = new JsonObject();
		r.addProperty("ptwebqq", ptwebqq);
		r.addProperty("clientid", Client_ID);
		r.addProperty("psessionid", psessionid);
		r.addProperty("key", "");

		pollRequest = postRequest(httpsChatMessage ? ApiURL.POLL_MESSAGE_HTTPS : ApiURL.POLL_MESSAGE, r,
				new Timeout(3, TimeUnit.MINUTES));
		pollRequest.send(new BufferingResponseListener() {
			@Override
			public void onComplete(Result result) {
				try {
					if (result.isFailed()) {
						Throwable failure = result.getFailure();
						if (!(failure instanceof RequestAbortException)) {
							LOGGER.error(failure.getMessage(), failure);
							listener.onException(failure, ExceptionThreadType.POLL_IO_THREAD);
						}
					} else {
						Response response = result.getResponse();
						ContentResponse contentResponse = new HttpContentResponse(response, getContent(),
								getMediaType(), getEncoding());
						JsonArray array = getJsonArrayResult(contentResponse);
						for (int i = 0; array != null && i < array.size(); i++) {
							JsonObject message = array.get(i).getAsJsonObject();
							String type = message.get("poll_type").getAsString();
							if ("message".equals(type)) {
								callback.onMessage(new Message(message.getAsJsonObject("value")));
							} else if ("group_message".equals(type)) {
								callback.onGroupMessage(new GroupMessage(message.getAsJsonObject("value")));
							} else if ("discu_message".equals(type)) {
								callback.onDiscussMessage(new DiscussMessage(message.getAsJsonObject("value")));
							}
						}
					}
				} catch (Exception ex) {
					LOGGER.error(ex.getMessage(), ex);
					listener.onException(ex, ExceptionThreadType.POLL_RESPONSE_LISTENER_THREAD);
				} finally {
					synchronized (pollWaitObject) {
						pollWaitObject.notify();
					}
				}
			}
		});
	}

	/**
	 * 发送群消息
	 *
	 * @param groupId
	 *            群id
	 * @param messageContentElements
	 *            消息内容
	 * @param font
	 *            消息字体
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public void sendMessageToGroup(long groupId, List<MessageContentElement> messageContentElements, Font font)
			throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始发送群消息");

		JsonObject r = new JsonObject();
		r.addProperty("group_uin", groupId);
		r.addProperty("content", MessageContentElementUtil.toContentJson(messageContentElements, font)); // 注意这里虽然格式是Json，但是实际是String
		r.addProperty("face", 573);
		r.addProperty("clientid", Client_ID);
		r.addProperty("msg_id", MESSAGE_ID++);
		r.addProperty("psessionid", psessionid);

		ContentResponse response = postWithRetry(
				httpsChatMessage ? ApiURL.SEND_MESSAGE_TO_GROUP_HTTPS : ApiURL.SEND_MESSAGE_TO_GROUP, r);
		checkSendMsgResult(response);
	}

	/**
	 * 发送讨论组消息
	 *
	 * @param discussId
	 *            讨论组id
	 * @param messageContentElements
	 *            消息内容
	 * @param font
	 *            消息字体
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public void sendMessageToDiscuss(long discussId, List<MessageContentElement> messageContentElements, Font font)
			throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始发送讨论组消息");

		JsonObject r = new JsonObject();
		r.addProperty("did", discussId);
		r.addProperty("content", MessageContentElementUtil.toContentJson(messageContentElements, font)); // 注意这里虽然格式是Json，但是实际是String
		r.addProperty("face", 573);
		r.addProperty("clientid", Client_ID);
		r.addProperty("msg_id", MESSAGE_ID++);
		r.addProperty("psessionid", psessionid);

		ContentResponse response = postWithRetry(
				httpsChatMessage ? ApiURL.SEND_MESSAGE_TO_DISCUSS_HTTPS : ApiURL.SEND_MESSAGE_TO_DISCUSS, r);
		checkSendMsgResult(response);
	}

	/**
	 * 发送消息
	 *
	 * @param friendId
	 *            好友id
	 * @param messageContentElements
	 *            消息内容
	 * @param font
	 *            消息字体
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public void sendMessageToFriend(long friendId, List<MessageContentElement> messageContentElements, Font font)
			throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始发送消息");

		JsonObject r = new JsonObject();
		r.addProperty("to", friendId);
		r.addProperty("content", MessageContentElementUtil.toContentJson(messageContentElements, font)); // 注意这里虽然格式是Json，但是实际是String
		r.addProperty("face", 573);
		r.addProperty("clientid", Client_ID);
		r.addProperty("msg_id", MESSAGE_ID++);
		r.addProperty("psessionid", psessionid);

		ContentResponse response = postWithRetry(
				httpsChatMessage ? ApiURL.SEND_MESSAGE_TO_FRIEND_HTTPS : ApiURL.SEND_MESSAGE_TO_FRIEND, r);
		checkSendMsgResult(response);
	}

	/**
	 * 获得讨论组列表
	 *
	 * @return 讨论组列表
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public List<Discuss> getDiscussList() throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取讨论组列表");

		ContentResponse response = get(ApiURL.GET_DISCUSS_LIST, psessionid, vfwebqq);
		return GsonUtil.gson.fromJson(getJsonObjectResult(response).get("dnamelist"), new TypeToken<List<Discuss>>() {
		}.getType());
	}

	/**
	 * 获得好友列表（包含分组信息）
	 *
	 * @return 好友列表（包含分组信息）
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public List<Category> getFriendListWithCategory()
			throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取好友列表");

		JsonObject r = new JsonObject();
		r.addProperty("vfwebqq", vfwebqq);
		r.addProperty("hash", hash());

		ContentResponse response = post(ApiURL.GET_FRIEND_LIST, r);
		JsonObject result = getJsonObjectResult(response);
		// 获得好友信息
		Map<Long, Friend> friendMap = parseFriendMap(result);
		// 获得分组
		JsonArray categories = result.getAsJsonArray("categories");
		Map<Integer, Category> categoryMap = new HashMap<>();
		categoryMap.put(0, Category.defaultCategory());
		for (int i = 0; categories != null && i < categories.size(); i++) {
			Category category = GsonUtil.gson.fromJson(categories.get(i), Category.class);
			categoryMap.put(category.getIndex(), category);
		}
		JsonArray friends = result.getAsJsonArray("friends");
		for (int i = 0; friends != null && i < friends.size(); i++) {
			JsonObject item = friends.get(i).getAsJsonObject();
			Friend friend = friendMap.get(item.get("uin").getAsLong());
			categoryMap.get(item.get("categories").getAsInt()).addFriend(friend);
		}
		return new ArrayList<>(categoryMap.values());
	}

	/**
	 * 获取好友列表
	 *
	 * @return 好友列表
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public List<Friend> getFriendList() throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取好友列表");

		JsonObject r = new JsonObject();
		r.addProperty("vfwebqq", vfwebqq);
		r.addProperty("hash", hash());

		ContentResponse response = post(ApiURL.GET_FRIEND_LIST, r);
		return new ArrayList<>(parseFriendMap(getJsonObjectResult(response)).values());
	}

	// 将json解析为好友列表
	private static Map<Long, Friend> parseFriendMap(JsonObject result) {
		Map<Long, Friend> friendMap = new HashMap<>();
		JsonArray info = result.getAsJsonArray("info");
		for (int i = 0; info != null && i < info.size(); i++) {
			JsonObject item = info.get(i).getAsJsonObject();
			Friend friend = new Friend();
			friend.setUserId(item.get("uin").getAsLong());
			friend.setNickname(item.get("nick").getAsString());
			friendMap.put(friend.getUserId(), friend);
		}
		JsonArray marknames = result.getAsJsonArray("marknames");
		for (int i = 0; marknames != null && i < marknames.size(); i++) {
			JsonObject item = marknames.get(i).getAsJsonObject();
			friendMap.get(item.get("uin").getAsLong()).setMarkname(item.get("markname").getAsString());
		}
		JsonArray vipinfo = result.getAsJsonArray("vipinfo");
		for (int i = 0; vipinfo != null && i < vipinfo.size(); i++) {
			JsonObject item = vipinfo.get(i).getAsJsonObject();
			Friend friend = friendMap.get(item.get("u").getAsLong());
			friend.setVip(item.get("is_vip").getAsInt() == 1);
			friend.setVipLevel(item.get("vip_level").getAsInt());
		}
		return friendMap;
	}

	/**
	 * 获得当前登录用户的详细信息
	 *
	 * @return 当前登录用户的详细信息
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public UserInfo getAccountInfo() throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取登录用户信息");

		ContentResponse response = get(ApiURL.GET_ACCOUNT_INFO);
		return GsonUtil.gson.fromJson(getJsonElementResult(response), UserInfo.class);
	}

	/**
	 * 获得好友的详细信息
	 *
	 * @param friendId
	 *            好友ID
	 * @return 好友的详细信息
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public UserInfo getFriendInfo(long friendId) throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取好友信息");

		ContentResponse response = get(ApiURL.GET_FRIEND_INFO, friendId, vfwebqq, psessionid);
		return GsonUtil.gson.fromJson(getJsonElementResult(response), UserInfo.class);
	}

	/**
	 * 获得最近会话列表
	 *
	 * @return 最近会话列表
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public List<Recent> getRecentList() throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取最近会话列表");

		JsonObject r = new JsonObject();
		r.addProperty("vfwebqq", vfwebqq);
		r.addProperty("clientid", Client_ID);
		r.addProperty("psessionid", "");

		ContentResponse response = post(ApiURL.GET_RECENT_LIST, r);
		return GsonUtil.gson.fromJson(getJsonElementResult(response), new TypeToken<List<Recent>>() {
		}.getType());
	}

	/**
	 * 获得QQ号
	 * 
	 * @param modelWithUserId
	 *            The model that contains the user id.
	 * @return QQ号
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public long getQQById(WithUserId modelWithUserId)
			throws InterruptedException, ExecutionException, TimeoutException {
		return getQQById(modelWithUserId.getUserId());
	}

	/**
	 * 获得QQ号
	 *
	 * @param userId
	 *            用户id
	 * @return QQ号
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public long getQQById(long userId) throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取QQ号");

		ContentResponse response = get(ApiURL.GET_QQ_BY_ID, userId, vfwebqq);
		return getJsonObjectResult(response).get("account").getAsLong();
	}

	/**
	 * 获得好友的状态
	 *
	 * @return 好友的状态
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public List<FriendStatus> getFriendStatus() throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取好友状态");

		ContentResponse response = get(ApiURL.GET_FRIEND_STATUS, vfwebqq, psessionid);
		return GsonUtil.gson.fromJson(getJsonElementResult(response), new TypeToken<List<FriendStatus>>() {
		}.getType());
	}

	/**
	 * 获得群的详细信息
	 *
	 * @param groupCode
	 *            群编号
	 * @return 群的详细信息
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public GroupInfo getGroupInfo(long groupCode) throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取群资料");

		ContentResponse response = get(ApiURL.GET_GROUP_INFO, groupCode, vfwebqq);
		JsonObject result = getJsonObjectResult(response);
		GroupInfo groupInfo = GsonUtil.gson.fromJson(result.get("ginfo"), GroupInfo.class);
		// 获得群成员信息
		Map<Long, GroupUser> groupUserMap = new HashMap<>();
		JsonArray minfo = result.getAsJsonArray("minfo");
		for (int i = 0; minfo != null && i < minfo.size(); i++) {
			GroupUser groupUser = GsonUtil.gson.fromJson(minfo.get(i), GroupUser.class);
			groupUserMap.put(groupUser.getUin(), groupUser);
			groupInfo.addUser(groupUser);
		}
		JsonArray stats = result.getAsJsonArray("stats");
		for (int i = 0; stats != null && i < stats.size(); i++) {
			JsonObject item = stats.get(i).getAsJsonObject();
			GroupUser groupUser = groupUserMap.get(item.get("uin").getAsLong());
			groupUser.setClientType(item.get("client_type").getAsInt());
			groupUser.setStatus(item.get("stat").getAsInt());
		}
		JsonArray cards = result.getAsJsonArray("cards");
		for (int i = 0; cards != null && i < cards.size(); i++) {
			JsonObject item = cards.get(i).getAsJsonObject();
			groupUserMap.get(item.get("muin").getAsLong()).setCard(item.get("card").getAsString());
		}
		JsonArray vipinfo = result.getAsJsonArray("vipinfo");
		for (int i = 0; vipinfo != null && i < vipinfo.size(); i++) {
			JsonObject item = vipinfo.get(i).getAsJsonObject();
			GroupUser groupUser = groupUserMap.get(item.get("u").getAsLong());
			groupUser.setVip(item.get("is_vip").getAsInt() == 1);
			groupUser.setVipLevel(item.get("vip_level").getAsInt());
		}
		return groupInfo;
	}

	/**
	 * 获得讨论组的详细信息
	 *
	 * @param discussId
	 *            讨论组id
	 * @return 讨论组的详细信息
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public DiscussInfo getDiscussInfo(long discussId)
			throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始获取讨论组资料");

		ContentResponse response = get(ApiURL.GET_DISCUSS_INFO, discussId, vfwebqq, psessionid);
		JsonObject result = getJsonObjectResult(response);
		DiscussInfo discussInfo = GsonUtil.gson.fromJson(result.get("info"), DiscussInfo.class);
		// 获得讨论组成员信息
		Map<Long, DiscussUser> discussUserMap = new HashMap<>();
		JsonArray minfo = result.getAsJsonArray("mem_info");
		for (int i = 0; minfo != null && i < minfo.size(); i++) {
			DiscussUser discussUser = GsonUtil.gson.fromJson(minfo.get(i), DiscussUser.class);
			discussUserMap.put(discussUser.getUin(), discussUser);
			discussInfo.addUser(discussUser);
		}
		JsonArray stats = result.getAsJsonArray("mem_status");
		for (int i = 0; stats != null && i < stats.size(); i++) {
			JsonObject item = stats.get(i).getAsJsonObject();
			DiscussUser discussUser = discussUserMap.get(item.get("uin").getAsLong());
			discussUser.setClientType(item.get("client_type").getAsInt());
			discussUser.setStatus(item.get("status").getAsString());
		}
		return discussInfo;
	}

	/**
	 * 修改用户状态
	 *
	 * @param userStatus
	 *            用户状态
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public void changeStatus(UserStatus userStatus) throws InterruptedException, ExecutionException, TimeoutException {
		LOGGER.debug("开始修改状态");

		ContentResponse response = get(ApiURL.CHANGE_STATUS, userStatus.getStatusCode(), psessionid);
		getResponseJson(response);
		this.selfUserStatus = userStatus.getStatusCode();
	}

	/**
	 * 获取用户头像
	 * 
	 * @param userId
	 *            用户ID
	 * @return 用户头像的图片内容
	 * @throws InterruptedException
	 *             if send thread is interrupted
	 * @throws ExecutionException
	 *             if execution fails
	 * @throws TimeoutException
	 *             if send times out
	 */
	public byte[] getUserFace(long userId) throws InterruptedException, TimeoutException, ExecutionException {
		return httpClient.newRequest(ApiURL.GET_USER_FACE.buildUrl(faceDomainSuffixRandom.nextInt(10), userId, vfwebqq))
				.method(HttpMethod.GET).agent(ApiURL.USER_AGENT)
				.header(HttpHeader.REFERER, ApiURL.GET_USER_FACE.getReferer()).send().getContent();
	}

	// 发送get请求
	private ContentResponse get(ApiURL url, Object... params)
			throws InterruptedException, ExecutionException, TimeoutException {
		Request request = httpClient.newRequest(url.buildUrl(params)).method(HttpMethod.GET).agent(ApiURL.USER_AGENT);
		if (url.getReferer() != null) {
			request.header(HttpHeader.REFERER, url.getReferer());
		}
		return request.send();
	}

	// 发送post请求，失败时重试
	private ContentResponse postWithRetry(ApiURL url, JsonObject r)
			throws InterruptedException, ExecutionException, TimeoutException {
		int times = 0;
		ContentResponse response;
		do {
			response = post(url, r);
			times++;
		} while (times < RETRY_TIMES && response.getStatus() != 200);
		return response;
	}

	// 发送post请求
	private ContentResponse post(ApiURL url, JsonObject r)
			throws InterruptedException, ExecutionException, TimeoutException {
		return post(url, r, null);
	}

	// 发送post请求
	private ContentResponse post(ApiURL url, JsonObject r, Timeout timeout)
			throws InterruptedException, ExecutionException, TimeoutException {
		return postRequest(url, r, timeout).send();
	}

	private Request postRequest(ApiURL url, JsonObject r, Timeout timeout) {
		Fields fields = new Fields();
		fields.add("r", GsonUtil.gson.toJson(r));
		Request request = httpClient.newRequest(url.getUrl()).method(HttpMethod.POST).agent(ApiURL.USER_AGENT)
				.header("Origin", url.getOrigin()).content(new FormContentProvider(fields));
		if (url.getReferer() != null) {
			request.header(HttpHeader.REFERER, url.getReferer());
		}
		if (timeout != null) {
			request.timeout(timeout.getTime(), timeout.getUnit());
		}
		return request;
	}

	// 获取返回json的result字段（JsonObject类型）
	private static JsonObject getJsonObjectResult(ContentResponse response) {
		return getResponseJson(response).getAsJsonObject().getAsJsonObject("result");
	}

	// 获取返回json的result字段（JsonArray类型）
	private static JsonArray getJsonArrayResult(ContentResponse response) {
		return getResponseJson(response).getAsJsonObject().getAsJsonArray("result");
	}

	// 获取返回json的result字段（JsonElement类型）
	private static JsonElement getJsonElementResult(ContentResponse response) {
		return getResponseJson(response).getAsJsonObject().get("result");
	}

	// 检查消息是否发送成功
	private static void checkSendMsgResult(ContentResponse response) {
		if (response.getStatus() != 200) {
			LOGGER.error(String.format("发送失败，Http返回码[%d]", response.getStatus()));
			throw new ResponseException(response.getStatus());
		}
		JsonElement json = GsonUtil.jsonParser.parse(response.getContentAsString());
		Integer errCode = json.getAsJsonObject().get("errCode").getAsInt();
		if (errCode != null && errCode == 0) {
			LOGGER.debug("发送成功!");
		} else {
			int apiReturnCode = json.getAsJsonObject().get("retcode").getAsInt();
			LOGGER.error(String.format("发送失败，Api返回码[%d]", apiReturnCode));
			throw new ApiException(apiReturnCode);
		}
	}

	// 检验Json返回结果
	private static JsonElement getResponseJson(ContentResponse response) {
		if (response.getStatus() != 200) {
			throw new ResponseException(response.getStatus());
		}
		JsonElement json = GsonUtil.jsonParser.parse(response.getContentAsString());
		Integer retCode = json.getAsJsonObject().get("retcode").getAsInt();
		if (retCode == null || retCode != 0) {
			if (retCode != null && retCode == 103) {
				LOGGER.error("请求失败，Api返回码[103]。你需要进入http://w.qq.com，检查是否能正常接收消息。如果可以的话点击[设置]->[退出登录]后查看是否恢复正常");
				throw new ApiException(
						"请求失败，Api返回码[103]。你需要进入http://w.qq.com，检查是否能正常接收消息。如果可以的话点击[设置]->[退出登录]后查看是否恢复正常");
			} else {
				throw new ApiException(retCode);
			}
		}
		return json;
	}

	// hash加密方法
	private String hash() {
		return hash(uin, ptwebqq);
	}

	// 线程暂停
	private static void sleep(long seconds) {
		try {
			Thread.sleep(seconds * 1000);
		} catch (InterruptedException ignored) {
		}
	}

	// hash加密方法
	private static String hash(long x, String K) {
		int[] N = new int[4];
		for (int T = 0; T < K.length(); T++) {
			N[T % 4] ^= K.charAt(T);
		}
		String[] U = { "EC", "OK" };
		long[] V = new long[4];
		V[0] = x >> 24 & 255 ^ U[0].charAt(0);
		V[1] = x >> 16 & 255 ^ U[0].charAt(1);
		V[2] = x >> 8 & 255 ^ U[1].charAt(0);
		V[3] = x & 255 ^ U[1].charAt(1);

		long[] U1 = new long[8];

		for (int T = 0; T < 8; T++) {
			U1[T] = T % 2 == 0 ? N[T >> 1] : V[T >> 1];
		}

		String[] N1 = { "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "A", "B", "C", "D", "E", "F" };
		String V1 = "";
		for (long aU1 : U1) {
			V1 += N1[(int) ((aU1 >> 4) & 15)];
			V1 += N1[(int) (aU1 & 15)];
		}
		return V1;
	}

	public String getSelfUserStatus() {
		return selfUserStatus;
	}

	public long getSelfUserId() {
		return uin;
	}

	@Override
	public long getUserId() {
		return uin;
	}

	@Override
	public void close() throws IOException {
		this.polling = false;
		try {
			if (this.pollThread != null) {
				this.pollThread.join();
			}
			this.httpClient.stop();
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	/**
	 * Cancels request and stops HTTP client.
	 * 
	 * @throws IOException
	 *             If the client fails to close
	 */
	public void closeNow() throws IOException {
		this.polling = false;
		try {
			synchronized (this.pollWaitObject) {
				if (this.pollRequest != null) {
					this.pollRequest.abort(new RequestAbortException());
				}
			}
			if (this.pollThread != null) {
				this.pollThread.join();
			}
			this.httpClient.stop();
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

}
