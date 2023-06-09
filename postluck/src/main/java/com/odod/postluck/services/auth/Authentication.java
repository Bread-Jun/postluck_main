package com.odod.postluck.services.auth;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import org.springframework.web.servlet.ModelAndView;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odod.postluck.beans.JWTBean;
import com.odod.postluck.beans.LocationBean;
import com.odod.postluck.beans.MenuBean;
import com.odod.postluck.beans.StoreBean;
import com.odod.postluck.services.pos.MainService;
import com.odod.postluck.utils.JsonWebTokenService;
import com.odod.postluck.utils.ProjectUtils;
import com.odod.postluck.utils.SimpleTransactionManager;
import com.odod.postluck.utils.TransactionAssistant;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class Authentication extends TransactionAssistant {
	@Autowired
	private JsonWebTokenService jwt;
	@Autowired
	private ProjectUtils pu;
	@Autowired
	private SimpleTransactionManager tranManager;
	@Autowired
	private MainService main;

	public Authentication() {
	}

	/* Ajax 방식의 요청 컨트롤러 */
	public void backController(String serviceCode, Model model) {
		switch (serviceCode) {
			case "AU01" :
				this.dupCheckStCodeCtl(model);
				break;
			case "AU02" :
				this.issuanceJWT(model);
				break;
			case "AU03" :
				this.regStoreInfo(model);
				break;
		}
	}

	/* View 방식의 요청 컨트롤러 */
	public void backController(String serviceCode, ModelAndView mav) {
		switch (serviceCode) {
			case "AU03" :
				this.accessCtl(mav);
				break;
			case "AU04" :
				this.logOut(mav);
				break;
		}
	}

	private void logOut(ModelAndView mav) {
		StoreBean store =(StoreBean) mav.getModel().get("store");
		// snsId를 가지고있음
		String jwt = mav.getModel().get("jwt").toString();
		try {
			store = (StoreBean) this.pu.getAttribute("AccessInfo");
			store.getAccessLogList().get(0).setAccessType('O');
			this.tranManager = this.getTransaction(false);
			this.tranManager.tranStart();
			if (this.convertToBoolean(this.sqlSession.insert("insAccessLog", store))) {
				if (this.sqlSession.selectOne("selSalesLog", store).equals('O')) {
					store.getSalesLogList().get(0).setSalesState('C');
					if (this.convertToBoolean(this.sqlSession.insert("insSalesLog", store))) {
						this.sqlSession.update("updStoreStatus", store);
					}
				}
				this.pu.removeAttribute("AccessInfo");
				mav.setViewName("index");
				this.tranManager.commit();
			} else {
				store.setMessage("로그아웃 실패");
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			this.tranManager.tranEnd();
		}
	}


	/**
	 * @method private void issuanceJWT(Model model)
	 * 
	 * @author 홍준택
	 *
	 * @brief 사용자의 snsID값을 받으면 accesslog를 insert하고 가입여부에 따라 jwt를 발급해주는 함수
	 * 
	 * @date 2023/02/22
	 * 
	 * @return void
	 * 
	 * @param storeBean(사용자의
	 *            SNSID)을 담은 model 객체
	 * 
	 */
	private void issuanceJWT(Model model) {
		// TODO Auto-generated method stub
		StoreBean store = (StoreBean) model.getAttribute("store");
		StoreBean st;
		// snsID, 사업자 이름, 이메일, 타입 ...
		JWTBean jwtBody;
		store.getAccessLogList().get(0).setAccessType('I');
		try {
			this.tranManager = this.getTransaction(false);
			this.tranManager.tranStart();
			if (this.convertToBoolean(
					this.sqlSession.insert("insAccessLog", store))) {
				if (this.convertToBoolean(
						this.sqlSession.selectOne("isSnsId", store))) {// 만약
																		// store정보가
																		// 등록되어있다면
					store.setStoreCode(
							this.sqlSession.selectOne("selStoreCode", store));
					st = (StoreBean) this.sqlSession
							.selectList("selStoreInfo", store).get(0);
					System.out.println("snsId로 select해온 storeBean : " + store);
					System.out.println(store.getStoreCode());
					store = null;
					// 사업자코드가 존재할 때 토큰 발행
					jwtBody = JWTBean.builder().storeCode(st.getStoreCode())
							.snsID(st.getSnsID()).build();
					this.pu.transferJWTByResponse(this.jwt
							.tokenIssuance(jwtBody, "JWTForPostluckFromODOD"));
					st.setMessage("true"); // 사업자 정보가 있음.
					model.addAttribute("store", st);
					this.pu.setAttribute("AccessInfo",
							model.getAttribute("store"));
					this.tranManager.commit();
				} else {
					// 사업자코드가 존재하지 않을 때 토큰 발행
					jwtBody = JWTBean.builder().snsID(store.getSnsID())
							.ceoEmail(store.getCeoEmail())
							.ceoName(store.getCeoName())
							.snsType(store.getSnsType()).build();
					this.pu.transferJWTByResponse(this.jwt
							.tokenIssuance(jwtBody, "JWTForPostluckFromODOD"));
					store.setMessage("false");
					System.out.println("issuanceJWT : " + store);
					this.pu.setAttribute("AccessInfo", store);
				}
			} else {
				store.setMessage("ins를 실패했습니다.");
				System.out.println("ins 실패");
			}
		} catch (Exception e) {
			e.printStackTrace();
			store.setMessage("error");
			this.tranManager.rollback();
		} finally {
			this.tranManager.tranEnd();
		}
	}

	/**
	 * @method private void accessCtl(ModelAndView mav)
	 * 
	 * @author 이예림
	 * 
	 * @brief 사용자의 snsID값을 받고 회원 정보가 있을 시 JWT 유효성을 검사하고 session에 사용자 정보를 set하고
	 *        message를 true로, 회원정보가 없을 시 storeBean에 회원 등록에 필요한 정보(이메일, snsType,
	 *        이름, snsID)를 저장한 뒤 message를 false로
	 * 
	 * @date 2023/02/22
	 * 
	 * @return void
	 * 
	 * @param storeBean(사용자의
	 *            SNSID)을 담은 model 객체
	 * @return
	 * 
	 */

	private void accessCtl(ModelAndView mav) {
		StoreBean store;
		List<MenuBean> menuList;
		List<LocationBean> locationList;
		// // snsId를 가지고있음
		String jwt = mav.getModel().get("jwt").toString();
		// Map<String, Object> tokenBody;
		store = (StoreBean) mav.getModel().get("store");
		store.setSnsID(this.jwt.getTokenInfoFromJWT(jwt).getSnsID());
		this.tranManager.tranStart();
		try {
			if (this.convertToBoolean(
					this.sqlSession.selectOne("isSnsId", store))) {
				// 회원정보가 있으면,
				store.setStoreCode(
						this.jwt.getTokenInfoFromJWT(jwt).getStoreCode());
				System.out.println("storeCode is not null");
				store = (StoreBean) this.sqlSession
						.selectList("selStoreInfo", store).get(0);
				menuList = this.sqlSession.selectList("selMenuList", store);
				locationList = this.sqlSession.selectList("selLocationList",
						store);
				store.setLocationList((ArrayList<LocationBean>) locationList);
				store.setMenuList((ArrayList<MenuBean>) menuList);
				System.out.println("store Info is " + store);
				store.setMessage("true");
				mav.addObject("store",
						new ObjectMapper().writeValueAsString(store));
			} else {
				store.setCeoName(
						this.jwt.getTokenInfoFromJWT(jwt).getCeoName());
				store.setCeoEmail(
						this.jwt.getTokenInfoFromJWT(jwt).getCeoEmail());
				store.setSnsType(
						this.jwt.getTokenInfoFromJWT(jwt).getSnsType());
				store.setMessage("false");
				mav.addObject("store", new ObjectMapper()
						.writeValueAsString(store)); /* storeBean -> json */
			}
			this.pu.setAttribute("AccessInfo", store);
			mav.setViewName("index-service");
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			this.tranManager.tranEnd();
		}
	}

	/**
	 * @author 홍준택
	 * 
	 * @brief 사용자의 store정보값을 받으면 DB(STORE)에 insert 후 결과값(true or false)
	 *        string형태로 반환
	 * 
	 * @date 2023/02/23
	 * 
	 * @return void
	 * 
	 * @param storeBean을
	 *            담은 model 객체
	 * 
	 */

	private void regStoreInfo(Model model) {
		String message = "error::매장 등록을 실패했습니다.:";
		StoreBean store = (StoreBean) model.getAttribute("store");
		System.out.println("refStoreInfo : " + store);
		JWTBean jwtBody;
		try {
			// StoreBean st = (StoreBean) this.pu.getAttribute("AccessInfo");
			this.tranManager.tranStart();
			if (store != null) {
				if (this.convertToBoolean(
						this.sqlSession.insert("insStore", store))) {
					this.tranManager.commit();
					jwtBody = JWTBean.builder().storeCode(store.getStoreCode())
							.snsID(store.getSnsID()).build();
					this.pu.transferJWTByResponse(this.jwt
							.tokenIssuance(jwtBody, "JWTForPostluckFromODOD"));
					store.setMessage("plain::매장 등록이 완료되었습니다!::");
					model.addAttribute("store",
							main.getStoreInfoAsStoreBean(model));
					this.pu.setAttribute("AccessInfo", store);

				} else {
					message = "error::매장 등록을 실패했습니다.메인페이지로 이동합니다.:moveIndex";
				}
			}

		} catch (Exception e) {
			this.tranManager.rollback();
			e.printStackTrace();
		} finally {
			store.setMessage(message);
			this.tranManager.tranEnd();
		}

	}

	/**
	 * @author 홍준택
	 * 
	 * @brief 사용자의 storeCode값을 받으면 DB에 storeCode가 존재하는지 확인후 결과값 select
	 * 
	 * @date 2023/02/23
	 * 
	 * @return void
	 * 
	 * @param storeBean(사용자의
	 *            storeCode)을 담은 model 객체
	 * 
	 */

	private void dupCheckStCodeCtl(Model model) {
		String messege = "Access Error:시스템 접속이 실패하였습니다.";
		StoreBean storeCode = (StoreBean) model.getAttribute("storeCode");
		if (storeCode.getStoreCode() != null) {
			this.tranManager = this.getTransaction(true);
			this.tranManager.tranStart();
			System.out.println(storeCode);
			if (this.convertToBoolean(
					this.sqlSession.selectOne("isStCode", storeCode))) {
				messege = "중복된 사업자번호 입니다.";
				storeCode.setMessage(messege);
			} else {
				messege = "사용 가능한 사업자번호 입니다.";
				storeCode.setMessage(messege);
			}
			this.tranManager.tranEnd();
		}
	}
}
