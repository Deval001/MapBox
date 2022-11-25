package com.oxitec.ui.map


import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mapbox.bindgen.Value
import com.mapbox.common.TileDataDomain
import com.mapbox.common.TileStore
import com.mapbox.common.TileStoreOptions
import com.mapbox.maps.OfflineManager
import com.mapbox.maps.ResourceOptions
import com.mapbox.maps.applyDefaultParams
import com.oxitec.R
import com.oxitec.Sqllite.DatabaseHelper
import com.oxitec.Sqllite.ProductModel
import com.oxitec.databinding.ActivityOfflineMapBinding
import com.oxitec.enums.Status
import com.oxitec.model.OfflineMapModel
import com.oxitec.model.OfflineMapTileModel
import com.oxitec.model.ProductBrandModel
import com.oxitec.ui.home.HomeActivity
import com.oxitec.ui.home.ViewModel.HomeViewModel
import com.oxitec.ui.sign_in.SignInActivity
import com.oxitec.utils.*
import org.koin.androidx.viewmodel.ext.android.viewModel


class OfflineMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOfflineMapBinding


    // Offline objects
    private val offlineRegion: TileStore by lazy {
        TileStore.create().also {
            // Set default access token for the created tile store instance
            it.setOption(
                TileStoreOptions.MAPBOX_ACCESS_TOKEN,
                TileDataDomain.MAPS,
                Value(getString(R.string.mapbox_access_token))
            )
        }
    }
    private val resourceOptions: ResourceOptions by lazy {
        ResourceOptions.Builder().applyDefaultParams(this).tileStore(offlineRegion).build()
    }
    private val offlineManager: OfflineManager by lazy {
        OfflineManager(resourceOptions)
    }


    var productModel: List<ProductModel?> = ArrayList<ProductModel>()
    var productDataList = ArrayList<ProductBrandModel>()
    var database: DatabaseHelper? = null
    var databaseHelper: DatabaseHelper? = null
    private val homeViewModel: HomeViewModel by viewModel()
    lateinit var mapAdapter: OfflineMapAdapter
    var mProgressDialog: MyCustomProgressDialog? = null

    companion object {
        fun getStartIntent(context: Context): Intent {
            return Intent(context, OfflineMapActivity::class.java)
        }
    }

    override fun onBackPressed() {
        start(HomeActivity.getStartIntent(this@OfflineMapActivity, "home"), "12")

    }


    var tinydb : TinyDB? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfflineMapBinding.inflate(layoutInflater)
        setContentView(binding.root)


        binding.menuIV.setImageResource(R.drawable.ic_back)
        binding.menuIV.setSafeOnClickListener {
            onBackPressed()
        }

        database = DatabaseHelper(this)
        databaseHelper = DatabaseHelper(this)

        mProgressDialog = MyCustomProgressDialog(this)
        tinydb = TinyDB(this@OfflineMapActivity)

//        setupGesturesManager()
        if (isNetworkAvailable())
            getProductBranding()
        else
            initData()
        setUpObserver()

    }



    private fun setUpObserver() {
        homeViewModel.productBrandingResponse.observe(this) {
            when (it.status) {
                Status.LOADING -> {
                    hideKeyboard()
                    mProgressDialog?.show()
                }
                Status.SUCCESS -> {
                    mProgressDialog?.dismiss()
                    productDataList.clear()
                    productDataList.add(
                        ProductBrandModel(
                            it.data?.data?.primaryColor.toString(),
                            it.data?.data?.secondaryColor.toString(),
                            it.data?.data?.titleColor.toString(),
                            it.data?.data?.smallTextColor.toString(),
                            it.data?.data?.productImage.toString()
                        )
                    )
                    setTheme()

                }


                Status.ERROR -> {
                    mProgressDialog?.dismiss()
                    showToasty(this, "Unauthenticated.", "2")
                    homeViewModel.logout()
                    start(SignInActivity.getStartIntent(this))
                    finishAffinity()
                }
                Status.UNAUTHORIZED -> {
                    mProgressDialog?.dismiss()
                    showToasty(this, it.message!!, "2")
                    homeViewModel.logout()
                    start(SignInActivity.getStartIntent(this))
                    finishAffinity()
                }
                Status.NETWORK_ERROR -> {
                    mProgressDialog?.dismiss()
                    showToasty(this, getString(R.string.no_internet), "2")
                    /*startActivity(Intent(this, NoInternetActivity::class.java))
                    finishAffinity()*/
                }
                else -> {}
            }
        }


    }


    fun setTheme() {
        databaseHelper?.deleteProductBranding()

        for (i in 0 until productDataList.size) {
            databaseHelper?.insertProductBranding(
                productDataList[i].primaryColor.toString(),
                productDataList[i].secondaryColor.toString(),
                productDataList[i].titleColor.toString(),
                productDataList[i].smallTextColor.toString(),
                productDataList[i].productImage.toString()
            )
        }


        initData()

    }

    private fun initData() {
        database = DatabaseHelper(this)
        productModel = database!!.getProductBrandingData()
        try {
            try {
                binding.toolbar.background.setColorFilter(
                    Color.parseColor(productModel[0]?.primaryColor),
                    PorterDuff.Mode.SRC_ATOP
                )

                binding.titleTV.setTextColor(Color.parseColor(productModel[0]?.titleColor))
                productModel[0]?.primaryColor?.let { changeStatusBarColor(this, it) }
                binding.menuIV.setTint(Color.parseColor(productModel[0]?.titleColor))

            } catch (e: Exception) {
                e.printStackTrace()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        binding.mainRL.visible()

        initRecyclerView()

    }


    var offlineMapDataTamp = ArrayList<Any>()
    var offlineMapData = ArrayList<OfflineMapTileModel>()

    private fun initRecyclerView() {
        binding.rvOfflineMap.apply {


            offlineMapDataTamp = tinydb!!.getListObject(Constant.OFFLINE_MAP_DATA, OfflineMapTileModel::class.java)

            for (item in offlineMapDataTamp){
                offlineMapData.add(item as OfflineMapTileModel)
            }

            Log.d("=== offlineMapData","$offlineMapData")



            mapAdapter =
                OfflineMapAdapter(this@OfflineMapActivity, offlineRegion, offlineManager)
//            mapAdapter.addAll(offlineMapData)


            offlineManager.getAllStylePacks { expected ->
                if (expected.isValue) {
                    expected.value?.let { stylePackList ->
                        Log.d("=== stylePackList", "${stylePackList.size}")
                    }
                }
                expected.error?.let { stylePackError ->

                }
            }

            var isMapCompleted = false
            val updateHandler = Handler()
            val regionsList = ArrayList<OfflineMapModel>()



            val runnable: Runnable = object : Runnable {
                override fun run() {
                    // do your stuff - don't create a new runnable here!


                    regionsList.clear()
                    mapAdapter.clear()
                    offlineRegion.getAllTileRegions { expected ->
                        if (expected.isValue) {
                            expected.value?.let { tileRegionList ->

                                for (region in tileRegionList) {

                                    Log.d("===", " requiredResourceCount :: ${region.requiredResourceCount} :::: completedResourceCount :: ${region.completedResourceCount}" )

                                    isMapCompleted =
                                        region.requiredResourceCount == region.completedResourceCount

                                    regionsList.add(
                                        OfflineMapModel(
                                            region.id,
                                            region
                                        )
                                    )
                                }

                                runOnUiThread {
                                    if (regionsList.isNotEmpty()) {
                                        mapAdapter.addAll(regionsList)
                                       /* binding.noDataLL.visibility = View.GONE
                                        binding.mapListRL.visibility = View.VISIBLE*/
                                    } /*else {
                                        binding.noDataLL.visibility = View.VISIBLE
                                        binding.mapListRL.visibility = View.GONE
                                    }*/


                                }

                            }
                        }
                        expected.error?.let { tileRegionError ->
                            Log.d("=== tileRegionError", tileRegionError.message)
                        }
                    }

                    if (!isMapCompleted) {
                        updateHandler.postDelayed(this, 1000)
                    }
                }
            }

            updateHandler.post(runnable);




            adapter = mapAdapter
            layoutManager = LinearLayoutManager(this@OfflineMapActivity)
        }

        /*mapAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                super.onChanged()
                checkEmpty()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                checkEmpty()
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                checkEmpty()
            }

            fun checkEmpty() {
                if (mapAdapter.itemCount == 0) {
                    binding.noDataLL.visibility = View.VISIBLE
                    binding.mapListRL.visibility = View.GONE
                } else {
                    binding.noDataLL.visibility = View.GONE
                    binding.mapListRL.visibility = View.VISIBLE
                }
            }
        })*/


    }

    private fun getProductBranding() {
        homeViewModel.getProductBranding()
    }
}