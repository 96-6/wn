import copy
import json
from pathlib import Path
import sys
import unittest
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import train_motion_model as training
import profile_audio as wn


class TrainingTests(unittest.TestCase):
    def profile(self):
        t=np.arange(120)*.08
        return {'version':1,'name':'Train','bands':[{'lowHz':80,'highHz':6000,'level':1}],
                'motion':{'stepMs':80,'pitchHz':(280+50*np.sin(t*4)).tolist(),'energy':(.5+.3*np.sin(t*8)).tolist()}}
    def test_manual_gradients_match_finite_differences(self):
        x,y,*_=training.data_from_motion(self.profile()['motion']);p=training.initial(1);_,g=training.numpy_loss(p,x,y,True)
        for matrix,index in [(0,(0,0)),(1,(3,)),(2,(5,2)),(3,(4,))]:
            value=p[matrix][index];eps=1e-5
            p[matrix][index]=value+eps;plus=training.numpy_loss(p,x,y)
            p[matrix][index]=value-eps;minus=training.numpy_loss(p,x,y);p[matrix][index]=value
            self.assertAlmostEqual((plus-minus)/(2*eps),g[matrix][index],places=5)
    def test_training_improves_fit_and_exports_portable_weights(self):
        profile,report=training.train(self.profile(),epochs=300,seed=3,device='cpu')
        self.assertLess(report['negative_log_likelihood_after'],report['negative_log_likelihood_before']-.5)
        self.assertEqual(335,report['parameters']);self.assertEqual(profile,wn.validate_profile(json.loads(json.dumps(profile))))
    def test_invalid_weights_and_normalizers_are_rejected(self):
        profile,_=training.train(self.profile(),epochs=10,device='cpu')
        for key,value in [('w1',[0]),('b1',[float('nan')]*16),('pitchScale',0),('type','unknown')]:
            broken=copy.deepcopy(profile);broken['motion']['model'][key]=value
            with self.assertRaises(wn.ProfileError):wn.validate_profile(broken)
